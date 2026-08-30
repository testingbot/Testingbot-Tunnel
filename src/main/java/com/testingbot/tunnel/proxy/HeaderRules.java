package com.testingbot.tunnel.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;

/**
 * A small grammar for editing headers, used by {@code --header} and {@code --response-header}.
 *
 * <pre>
 *   name: value   set the header, replacing any the peer sent
 *   name;         set the header to an empty value
 *   -name         remove the header
 *   -name*        remove every header whose name starts with the prefix
 * </pre>
 *
 * <p>{@code --extra-headers} could only add request headers. Removing one was impossible, which
 * matters when the ambient environment injects something a staging site reacts badly to, and so
 * was overriding a response header -- the usual reason being a {@code Content-Security-Policy}
 * or {@code Strict-Transport-Security} that stops a test page from loading.
 *
 * <p>Rules apply in a fixed order regardless of how they were written: removals first, then
 * sets. Otherwise {@code -X} and {@code X: 1} together would mean different things depending on
 * argument order, which is not something anyone should have to reason about.
 */
public final class HeaderRules {

    /** RFC 7230 token, matching what --extra-headers already accepts. */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    private static final HeaderRules EMPTY = new HeaderRules(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

    private final List<String> removals;      // lower-case exact names
    private final List<String> removalPrefixes;   // lower-case prefixes
    private final Map<String, String> sets;   // original-case name to value

    private HeaderRules(List<String> removals, List<String> removalPrefixes,
                        Map<String, String> sets) {
        this.removals = removals;
        this.removalPrefixes = removalPrefixes;
        this.sets = sets;
    }

    public static HeaderRules none() {
        return EMPTY;
    }

    /**
     * @throws IllegalArgumentException if a rule is malformed, so a typo is reported at startup
     *         rather than silently dropping a header the user expected to be set
     */
    public static HeaderRules parse(String[] rules) {
        if (rules == null || rules.length == 0) {
            return EMPTY;
        }
        List<String> removals = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        Map<String, String> sets = new LinkedHashMap<>();

        for (String raw : rules) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String rule = raw.trim();
            if (rule.startsWith("-")) {
                String name = rule.substring(1).trim();
                boolean prefix = name.endsWith("*");
                if (prefix) {
                    name = name.substring(0, name.length() - 1).trim();
                }
                requireName(name, rule);
                (prefix ? prefixes : removals).add(name.toLowerCase(Locale.ROOT));
                continue;
            }
            int colon = rule.indexOf(':');
            if (colon > 0) {
                String name = rule.substring(0, colon).trim();
                String value = rule.substring(colon + 1).trim();
                requireName(name, rule);
                requireValue(name, value);
                sets.put(name, value);
                continue;
            }
            if (rule.endsWith(";")) {
                String name = rule.substring(0, rule.length() - 1).trim();
                requireName(name, rule);
                sets.put(name, "");
                continue;
            }
            throw new IllegalArgumentException("Invalid header rule '" + rule
                    + "'. Expected 'name: value', 'name;', '-name' or '-name*'.");
        }
        if (removals.isEmpty() && prefixes.isEmpty() && sets.isEmpty()) {
            return EMPTY;
        }
        return new HeaderRules(Collections.unmodifiableList(removals),
                Collections.unmodifiableList(prefixes),
                Collections.unmodifiableMap(sets));
    }

    private static void requireName(String name, String rule) {
        if (name.isEmpty() || !HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid header name in rule '" + rule
                    + "' (must be an RFC 7230 token)");
        }
    }

    private static void requireValue(String name, String value) {
        // A value carrying CR or LF would let a rule inject extra headers, or split the
        // message entirely, into every request the tunnel makes.
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "Header value for '" + name + "' must not contain CR or LF");
        }
    }

    public boolean isEmpty() {
        return removals.isEmpty() && removalPrefixes.isEmpty() && sets.isEmpty();
    }

    /** True when the peer's copy of {@code name} should not be passed on. */
    public boolean drops(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (removals.contains(lower)) {
            return true;
        }
        for (String prefix : removalPrefixes) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        // A header we are going to set replaces whatever the peer sent, so its copy goes too.
        for (String name2 : sets.keySet()) {
            if (name2.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /** Writes the {@code name: value} and {@code name;} rules into {@code fields}. */
    public void applySets(HttpFields.Mutable fields) {
        for (Map.Entry<String, String> entry : sets.entrySet()) {
            fields.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Applies every rule to a header set we own outright, which is the request case: removals
     * and replaced headers are stripped, then the sets are written.
     */
    public void applyTo(HttpFields.Mutable fields) {
        if (isEmpty()) {
            return;
        }
        List<String> doomed = new ArrayList<>();
        for (HttpField field : fields) {
            if (drops(field.getName())) {
                doomed.add(field.getName());
            }
        }
        for (String name : doomed) {
            fields.remove(name);
        }
        applySets(fields);
    }
}
