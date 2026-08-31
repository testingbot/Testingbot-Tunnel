package com.testingbot.tunnel.pac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A parsed {@code FindProxyForURL} return value.
 *
 * <p>The string is a {@code ;}-separated failover list, e.g.
 * {@code "PROXY a:8080; PROXY b:8080; DIRECT"}. Unparseable entries are dropped rather than
 * failing the whole result: a single typo in one branch of a long PAC file should not take the
 * tunnel offline when the remaining entries are usable.
 */
public final class PacResult {

    /** One directive from the failover list. */
    public static final class Entry {
        private final Kind kind;
        private final String host;
        private final int port;

        Entry(Kind kind, String host, int port) {
            this.kind = kind;
            this.host = host;
            this.port = port;
        }

        public Kind getKind() {
            return kind;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public boolean isDirect() {
            return kind == Kind.DIRECT;
        }

        /** The {@code --proxy} spelling of this entry, or null for DIRECT. */
        public String toProxySpec() {
            if (kind == Kind.DIRECT) {
                return null;
            }
            return (kind == Kind.SOCKS ? "socks5://" : "") + host + ":" + port;
        }

        @Override
        public String toString() {
            return kind == Kind.DIRECT ? "DIRECT" : kind + " " + host + ":" + port;
        }
    }

    public enum Kind {
        DIRECT,
        PROXY,
        SOCKS
    }

    private static final PacResult DIRECT_ONLY =
            new PacResult(List.of(new Entry(Kind.DIRECT, null, -1)));

    private final List<Entry> entries;

    private PacResult(List<Entry> entries) {
        this.entries = entries;
    }

    public static PacResult direct() {
        return DIRECT_ONLY;
    }

    public static PacResult parse(String directives) {
        if (directives == null || directives.isBlank()) {
            // The spec treats an empty result as DIRECT; so does every browser.
            return DIRECT_ONLY;
        }
        List<Entry> entries = new ArrayList<>();
        for (String raw : directives.split(";")) {
            Entry entry = parseEntry(raw.trim());
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries.isEmpty() ? DIRECT_ONLY : new PacResult(Collections.unmodifiableList(entries));
    }

    private static Entry parseEntry(String directive) {
        if (directive.isEmpty()) {
            return null;
        }
        String[] parts = directive.split("\\s+", 2);
        String keyword = parts[0].toUpperCase(Locale.ROOT);
        if (keyword.equals("DIRECT")) {
            return new Entry(Kind.DIRECT, null, -1);
        }
        if (parts.length < 2) {
            return null;
        }
        Kind kind;
        int defaultPort;
        switch (keyword) {
            case "PROXY":
            case "HTTP":
                kind = Kind.PROXY;
                defaultPort = 80;
                break;
            case "SOCKS":
            case "SOCKS5":
                kind = Kind.SOCKS;
                defaultPort = 1080;
                break;
            case "HTTPS":
                // An HTTPS proxy means TLS to the proxy itself, which the tunnel's egress paths
                // do not implement; treating it as plain HTTP would silently downgrade it.
                return null;
            default:
                return null;
        }
        String address = parts[1].trim();
        int colon = address.lastIndexOf(':');
        // Bracketed IPv6 keeps its colons; only a colon after the closing bracket is a port.
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            colon = close < 0 ? -1 : address.indexOf(':', close);
        }
        if (colon < 0) {
            return address.isEmpty() ? null : new Entry(kind, address, defaultPort);
        }
        try {
            int port = Integer.parseInt(address.substring(colon + 1).trim());
            if (port < 1 || port > 65535) {
                return null;
            }
            String host = address.substring(0, colon).trim();
            return host.isEmpty() ? null : new Entry(kind, host, port);
        } catch (NumberFormatException notAPort) {
            return null;
        }
    }

    public List<Entry> getEntries() {
        return entries;
    }

    /** The entry to try first. */
    public Entry first() {
        return entries.get(0);
    }

    /** True when the only instruction is to connect directly. */
    public boolean isDirect() {
        return entries.size() == 1 && entries.get(0).isDirect();
    }

    @Override
    public String toString() {
        List<String> rendered = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            rendered.add(entry.toString());
        }
        return String.join("; ", rendered);
    }
}
