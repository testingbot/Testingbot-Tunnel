package com.testingbot.tunnel.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dial-time host and port remapping, in the shape curl's {@code --connect-to} uses:
 * {@code HOST1:PORT1:HOST2:PORT2}.
 *
 * <p>A request for {@code HOST1:PORT1} connects to {@code HOST2:PORT2} instead, while the URL,
 * the {@code Host} header and the TLS SNI name stay as they were. That distinction is the whole
 * point: it lets a test drive {@code https://prod.example.com/} against a staging instance
 * reached through the tunnel, exercising the same virtual host and certificate name the real
 * site uses. Editing {@code /etc/hosts} is the usual alternative and needs root, affects every
 * process on the machine, and cannot redirect a port.
 *
 * <p>As in curl, an empty {@code HOST1} or {@code PORT1} matches anything, and an empty
 * {@code HOST2} or {@code PORT2} leaves that half of the destination alone. The first entry that
 * matches wins.
 *
 * <p>IPv6 literals must be bracketed on both sides, e.g.
 * {@code example.com:443:[::1]:8443}, so the colons in the address are not read as separators.
 */
public final class ConnectToMap {

    private static final Logger LOG = Logger.getLogger(ConnectToMap.class.getName());

    private static final ConnectToMap EMPTY = new ConnectToMap(Collections.emptyList());

    private final List<Rule> rules;

    private ConnectToMap(List<Rule> rules) {
        this.rules = rules;
    }

    public static ConnectToMap none() {
        return EMPTY;
    }

    public static ConnectToMap parse(String[] entries) {
        if (entries == null || entries.length == 0) {
            return EMPTY;
        }
        List<Rule> parsed = new ArrayList<>(entries.length);
        for (String entry : entries) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            Rule rule = Rule.parse(entry.trim());
            if (rule == null) {
                LOG.log(Level.WARNING,
                        "Ignoring invalid --connect-to entry ''{0}''; expected HOST1:PORT1:HOST2:PORT2",
                        entry.trim());
                continue;
            }
            parsed.add(rule);
        }
        return parsed.isEmpty() ? EMPTY : new ConnectToMap(Collections.unmodifiableList(parsed));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** @return where to actually dial, or the original host and port when no rule matches */
    public Target remap(String host, int port) {
        if (host == null) {
            return new Target(null, port);
        }
        String bare = FastFailPolicy.normalise(host);
        for (Rule rule : rules) {
            if (rule.matches(bare, port)) {
                Target target = new Target(rule.toHost == null ? host : rule.toHost,
                        rule.toPort < 0 ? port : rule.toPort);
                LOG.log(Level.FINE, "--connect-to: dialling {0}:{1} for {2}:{3}",
                        new Object[]{target.host(), target.port(), host, port});
                return target;
            }
        }
        return new Target(host, port);
    }

    /** Where a connection should actually be made. */
    public record Target(String host, int port) {
    }

    private static final class Rule {
        private final String fromHost;   // null means "any"
        private final int fromPort;      // -1 means "any"
        private final String toHost;     // null means "unchanged"
        private final int toPort;        // -1 means "unchanged"

        private Rule(String fromHost, int fromPort, String toHost, int toPort) {
            this.fromHost = fromHost;
            this.fromPort = fromPort;
            this.toHost = toHost;
            this.toPort = toPort;
        }

        boolean matches(String host, int port) {
            return (fromHost == null || fromHost.equals(host))
                    && (fromPort < 0 || fromPort == port);
        }

        static Rule parse(String entry) {
            List<String> fields = split(entry);
            if (fields == null || fields.size() != 4) {
                return null;
            }
            try {
                String fromHost = blankToNull(fields.get(0));
                String toHost = blankToNull(fields.get(2));
                int fromPort = parsePort(fields.get(1));
                int toPort = parsePort(fields.get(3));
                if (fromHost == null && fromPort < 0 && toHost == null && toPort < 0) {
                    return null;   // matches everything, changes nothing
                }
                // Normalise both sides the same way, or a bracketed IPv6 source could never
                // match the unbracketed host the dial path hands us.
                return new Rule(fromHost == null ? null : FastFailPolicy.normalise(fromHost),
                        fromPort,
                        toHost == null ? null : FastFailPolicy.normalise(toHost),
                        toPort);
            } catch (NumberFormatException notAPort) {
                return null;
            }
        }

        /**
         * Splits on colons, keeping bracketed IPv6 literals intact.
         *
         * <p>A plain {@code split(":")} would tear {@code [::1]} into pieces, so the four fields
         * could never be recovered.
         */
        private static List<String> split(String entry) {
            List<String> fields = new ArrayList<>(4);
            StringBuilder current = new StringBuilder();
            int depth = 0;
            for (int i = 0; i < entry.length(); i++) {
                char c = entry.charAt(i);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth < 0) {
                        return null;
                    }
                }
                if (c == ':' && depth == 0) {
                    fields.add(current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            if (depth != 0) {
                return null;
            }
            fields.add(current.toString().trim());
            return fields;
        }

        private static String blankToNull(String value) {
            return value.isEmpty() ? null : value;
        }

        private static int parsePort(String value) {
            if (value.isEmpty()) {
                return -1;
            }
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("port out of range: " + port);
            }
            return port;
        }
    }
}
