package com.testingbot.tunnel.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Turns a request body into something safe to write to a log file.
 *
 * <p>The reason this class exists rather than a {@code new String(body)} at the call site: the
 * Selenium relay carries WebDriver capabilities, and it is normal for those to hold the
 * customer's own access key. A body log that printed them verbatim would put credentials into a
 * file people paste into support tickets.
 *
 * <p>Three rules, and the third is the one that does the work:
 *
 * <ol>
 *   <li><b>Redact by key, not by pattern.</b> The structure is parsed and any value whose key
 *       looks like a credential is replaced. Scanning a blob for things that resemble secrets
 *       fails open -- anything unrecognised gets printed.</li>
 *   <li><b>Only show what can be parsed.</b> JSON and form encoding are understood, so they can
 *       be redacted and shown. Everything else is described by type and length and never
 *       printed, because a structure we cannot parse is one we cannot redact.</li>
 *   <li><b>Oversized bodies are described, not truncated.</b> Half a JSON document does not
 *       parse, so it could not be redacted -- and truncating first would mean printing raw
 *       bytes exactly when there are the most of them.</li>
 * </ol>
 */
public final class BodyRedactor {

    /** Bodies larger than this are described rather than shown. */
    public static final int MAX_BODY_BYTES = 16 * 1024;

    static final String REDACTED = "<redacted>";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Key fragments that mark a value as a credential.
     *
     * <p>Matched as substrings of the normalised key, so {@code tb:options.accessKey},
     * {@code access_key} and {@code ACCESSKEY} are all caught. Over-redaction is the safe
     * direction: a field wrongly hidden costs a debugging session, a field wrongly shown costs
     * a credential.
     */
    private static final List<String> SENSITIVE_FRAGMENTS = List.of(
            "secret", "password", "passwd", "pwd", "token", "credential",
            "apikey", "accesskey", "auth", "cookie", "session", "signature", "passphrase");

    /**
     * Words that are credentials on their own but too short to match as fragments.
     *
     * <p>Matched against each word of the key as well as the whole of it. Whole-key matching
     * alone missed {@code client_key} -- which is the name this project itself sends the
     * credential under (HttpProxy sends {@code client_key} and {@code client_secret}), so the
     * secret was redacted and the key beside it was not.
     */
    private static final List<String> SENSITIVE_WORDS =
            List.of("key", "pass", "sig", "secret", "token", "credential", "auth", "cookie");

    private BodyRedactor() {
    }

    /** True when a field with this name should have its value hidden. */
    static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        // Normalised so that access_key, access-key, "tb:accessKey" and ACCESSKEY are one thing.
        String normalised = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (SENSITIVE_WORDS.contains(normalised)) {
            return true;
        }
        // Split on the separators and on camelCase humps, so client_key and clientKey both
        // yield the word "key" while "monkey" stays one word and is not redacted.
        for (String word : key.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (SENSITIVE_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (String fragment : SENSITIVE_FRAGMENTS) {
            if (normalised.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A loggable rendering of {@code body}.
     *
     * @param contentType the declared type, or null
     * @param body        the bytes as read, or null
     * @return redacted content for a type we can parse, a description otherwise. Never the raw
     *         bytes of anything unparsed.
     */
    public static String render(String contentType, byte[] body) {
        if (body == null || body.length == 0) {
            return "<empty>";
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (body.length > MAX_BODY_BYTES) {
            return describe(contentType, body.length,
                    "larger than the " + MAX_BODY_BYTES + " byte limit; a partial body cannot be"
                    + " parsed, so it cannot be redacted");
        }
        if (type.contains("json")) {
            return json(body, contentType);
        }
        if (type.contains("x-www-form-urlencoded")) {
            return form(body, contentType);
        }
        return describe(contentType, body.length,
                "not a type this can parse, so it is not shown");
    }

    private static String json(byte[] body, String contentType) {
        try {
            JsonNode root = MAPPER.readTree(body);
            redact(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception malformed) {
            // Declared as JSON but is not. Showing it anyway would be showing an unparsed body,
            // which is the thing this class exists to avoid.
            return describe(contentType, body.length, "declared as JSON but could not be parsed");
        }
    }

    /** Walks the tree replacing sensitive values in place, at any depth. */
    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<String> names = object.fieldNames();
            List<String> fields = new java.util.ArrayList<>();
            while (names.hasNext()) {
                fields.add(names.next());
            }
            for (String field : fields) {
                if (isSensitiveKey(field)) {
                    object.put(field, REDACTED);
                } else {
                    redact(object.get(field));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                redact(child);
            }
        }
    }

    /**
     * Form encoding, or a description when the body is not actually a form.
     *
     * <p>The check that every segment is {@code name=value} is what makes rule 2 hold here. This
     * used to trust the declared content type: a body with no {@code &} is one segment, a body
     * with no {@code =} made the whole body the name, and the name was appended before anything
     * decided whether it was sensitive -- so a JSON capabilities payload sent as
     * {@code x-www-form-urlencoded}, which is what {@code curl -d @caps.json} does by default,
     * was printed in full with the key and secret in cleartext.
     */
    private static String form(byte[] body, String contentType) {
        String raw = new String(body, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                // No name, or no value: this is not form encoding, whatever it claims to be.
                return describe(contentType, body.length,
                        "declared as form encoding but is not name=value pairs");
            }
            String name = pair.substring(0, equals);
            String decoded;
            try {
                decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException notEncoded) {
                decoded = name;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            if (isSensitiveKey(decoded)) {
                // The name goes too: a name long enough to hold a credential often is one.
                out.append(REDACTED);
            } else {
                out.append(escapeControls(name)).append('=')
                        .append(escapeControls(pair.substring(equals + 1)));
            }
        }
        return out.length() == 0
                ? describe(contentType, body.length, "declared as form encoding but empty")
                : out.toString();
    }

    /**
     * Percent-encodes control characters so a body cannot forge log records.
     *
     * <p>Form segments are split on {@code &} and appended as text, so a value containing a
     * literal CR/LF used to reach the log intact -- and a log line the reader believes came from
     * this process is exactly what an attacker wants to write. A well-formed client already
     * sends these percent-encoded; this only makes it true of every client.
     *
     * <p>Encoded rather than stripped, so the bytes that were actually sent remain visible: a
     * request that tried this is worth being able to see afterwards. The JSON path needs none of
     * this because it re-serialises through Jackson, which escapes control characters on the way
     * out; this one concatenates strings, which is the whole difference.
     */
    static String escapeControls(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (out == null) {
                    out = new StringBuilder(value.length() + 8).append(value, 0, i);
                }
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    private static String describe(String contentType, int length, String why) {
        return "<" + (contentType == null || contentType.isBlank() ? "no content-type"
                : contentType) + ", " + length + " bytes, not shown: " + why + ">";
    }
}
