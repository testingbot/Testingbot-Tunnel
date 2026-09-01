package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.proxy.HttpLogHandler.Mode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code --log-http}, per module as well as globally.
 *
 * <p>The tunnel logs two quite different streams: browser traffic through the local proxy, and
 * the Selenium relay. Debugging one usually means drowning in the other, which a single global
 * setting cannot express -- {@code --log-http proxy:none,forwarder:headers} can.
 *
 * <p>A bare level still means what it always did and sets every module at once, so
 * {@code --log-http url} keeps working.
 */
public final class LogHttpPolicy {

    /** Browser and API traffic through the local proxy. */
    public static final String PROXY = "proxy";
    /** The Selenium relay on the {@code --se-port}. */
    public static final String FORWARDER = "forwarder";

    private static final List<String> MODULES = List.of(PROXY, FORWARDER);

    private final Mode defaultMode;
    private final Map<String, Mode> perModule;

    private LogHttpPolicy(Mode defaultMode, Map<String, Mode> perModule) {
        this.defaultMode = defaultMode;
        this.perModule = Map.copyOf(perModule);
    }

    /** The default when nothing is configured: failures only. */
    public static LogHttpPolicy defaults() {
        return new LogHttpPolicy(Mode.ERRORS, Map.of());
    }

    /**
     * @param value a bare level, or comma-separated {@code module:level} pairs, or both
     * @throws IllegalArgumentException naming the unknown module or level
     */
    public static LogHttpPolicy parse(String value) {
        if (value == null || value.isBlank()) {
            return defaults();
        }
        Mode fallback = null;
        Map<String, Mode> modules = new LinkedHashMap<>();
        for (String raw : value.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon < 0) {
                // A bare level. Last one wins rather than being an error: it reads as "and
                // everything else", which is how the equivalent is written elsewhere.
                fallback = level(entry);
                continue;
            }
            String module = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!MODULES.contains(module)) {
                throw new IllegalArgumentException("Unknown --log-http module '" + module
                        + "'. Valid modules are " + String.join(", ", MODULES) + ".");
            }
            modules.put(module, level(entry.substring(colon + 1)));
        }
        // No bare level given: modules named are set, everything else keeps the default.
        return new LogHttpPolicy(fallback == null ? Mode.ERRORS : fallback, modules);
    }

    private static Mode level(String value) {
        try {
            return Mode.parse(value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Invalid --log-http value: " + value.trim()
                    + ". Expected none, url, headers or errors.");
        }
    }

    /** The level for one module, falling back to the global one. */
    public Mode modeFor(String module) {
        return perModule.getOrDefault(module, defaultMode);
    }

    @Override
    public String toString() {
        if (perModule.isEmpty()) {
            return defaultMode.name().toLowerCase(Locale.ROOT);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Mode> entry : perModule.entrySet()) {
            out.append(entry.getKey()).append(':')
                    .append(entry.getValue().name().toLowerCase(Locale.ROOT)).append(',');
        }
        return out.append(defaultMode.name().toLowerCase(Locale.ROOT)).toString();
    }
}
