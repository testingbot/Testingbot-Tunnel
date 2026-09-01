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
            "apikey", "accesskey", "auth", "cookie", "session", "signature");

    /** Keys that are credentials on their own but too short to match as fragments. */
    private static final List<String> SENSITIVE_EXACT = List.of("key", "pass", "sig");

    private BodyRedactor() {
    }

    /** True when a field with this name should have its value hidden. */
    static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        // Normalised so that access_key, access-key, "tb:accessKey" and ACCESSKEY are one thing.
        String normalised = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (SENSITIVE_EXACT.contains(normalised)) {
            return true;
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
            return form(body);
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

    private static String form(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String decoded;
            try {
                decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException notEncoded) {
                decoded = name;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(name).append('=');
            out.append(isSensitiveKey(decoded) ? REDACTED
                    : (equals < 0 ? "" : pair.substring(equals + 1)));
        }
        return out.toString();
    }

    private static String describe(String contentType, int length, String why) {
        return "<" + (contentType == null || contentType.isBlank() ? "no content-type"
                : contentType) + ", " + length + " bytes, not shown: " + why + ">";
    }
}
