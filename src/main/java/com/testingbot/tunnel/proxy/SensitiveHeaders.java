package com.testingbot.tunnel.proxy;

import java.util.Locale;
import java.util.Set;

final class SensitiveHeaders {
    static final String REDACTED = "<redacted>";

    private static final Set<String> NAMES = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token"
    );

    private SensitiveHeaders() {
    }

    static boolean isSensitive(String name) {
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    static String redactValue(String name, String value) {
        return isSensitive(name) ? REDACTED : value;
    }
}
