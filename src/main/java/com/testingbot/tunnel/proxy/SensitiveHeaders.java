package com.testingbot.tunnel.proxy;

import java.util.Locale;
import java.util.Set;

/**
 * What must not reach whatever collects these logs.
 *
 * <p>Header names and request targets both, because a credential travels in either: the default
 * {@code --log-http errors} mode prints the full request URI for every failed request, and a
 * query string is where a signed URL or an access key routinely sits.
 */
final class SensitiveHeaders {
    static final String REDACTED = "<redacted>";

    private static final Set<String> NAMES = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            // The Selenium relay's own: the account key and secret, joined by an underscore.
            "tb-credentials"
    );

    private SensitiveHeaders() {
    }

    static boolean isSensitive(String name) {
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    static String redactValue(String name, String value) {
        return isSensitive(name) ? REDACTED : value;
    }

    /**
     * A request target with its userinfo and any credential-shaped query values removed.
     *
     * <p>Parsed by hand rather than through {@link java.net.URI}: this proxy exists partly to
     * forward the lenient query strings browsers send and {@code URI} rejects, so anything that
     * failed to parse would be logged unredacted -- and a target that reaches the log is one
     * this code has already decided to be lenient about.
     *
     * <p>Query keys are judged by {@link BodyRedactor#isSensitiveKey}, so a name recognised in a
     * request body is recognised here too.
     */
    static String redactUrl(String target) {
        if (target == null || target.isEmpty()) {
            return target;
        }
        String result = redactUserInfo(target);
        int query = result.indexOf('?');
        if (query < 0) {
            return result;
        }
        int end = result.indexOf('#', query);
        String prefix = result.substring(0, query + 1);
        String suffix = end < 0 ? "" : result.substring(end);
        String queryString = end < 0 ? result.substring(query + 1) : result.substring(query + 1, end);

        StringBuilder redacted = new StringBuilder(prefix);
        String[] parameters = queryString.split("&", -1);
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                redacted.append('&');
            }
            String parameter = parameters[i];
            int equals = parameter.indexOf('=');
            if (equals > 0 && BodyRedactor.isSensitiveKey(parameter.substring(0, equals))) {
                redacted.append(parameter, 0, equals + 1).append(REDACTED);
            } else {
                redacted.append(parameter);
            }
        }
        return redacted.append(suffix).toString();
    }

    /** {@code http://user:password@host/} -- the password is a credential like any other. */
    private static String redactUserInfo(String target) {
        int schemeEnd = target.indexOf("://");
        if (schemeEnd < 0) {
            return target;
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = target.length();
        for (int i = authorityStart; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        int at = target.lastIndexOf('@', authorityEnd - 1);
        if (at < authorityStart) {
            return target;
        }
        return target.substring(0, authorityStart) + REDACTED + target.substring(at);
    }
}
