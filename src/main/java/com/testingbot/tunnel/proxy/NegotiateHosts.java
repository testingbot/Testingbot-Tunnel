package com.testingbot.tunnel.proxy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The hosts that may be sent SPNEGO credentials, from {@code --krb5-hosts}.
 *
 * <p>An allowlist rather than a pattern, and empty by default, because this is the one place the
 * tunnel hands a Kerberos service ticket to something that is not a proxy. A ticket names the
 * user, and a host that receives one can prove to the KDC that the user talked to it. Sending
 * that to whatever a test happens to navigate to would be a real leak, so the rule is that a
 * host gets a ticket only if it was written down.
 *
 * <p>Matching is on host alone, not host and port: a Kerberos service principal is
 * {@code HTTP/host}, with no port in it, so a per-port list would imply a distinction the
 * protocol does not make.
 */
public final class NegotiateHosts {

    private static final NegotiateHosts NONE = new NegotiateHosts(Set.of());

    /**
     * Insertion-ordered on purpose. Set.copyOf randomises iteration order per JVM, which made
     * both the diagnostic output and this class's own ordering assertions non-deterministic --
     * a list a user wrote down should be reported back in the order they wrote it.
     */
    private final Set<String> hosts;

    private NegotiateHosts(Set<String> hosts) {
        this.hosts = Collections.unmodifiableSet(new LinkedHashSet<>(hosts));
    }

    public static NegotiateHosts none() {
        return NONE;
    }

    /**
     * @param entries comma-separated host names, or null
     * @throws IllegalArgumentException if an entry cannot be a host name
     */
    public static NegotiateHosts parse(String entries) {
        if (entries == null || entries.isBlank()) {
            return NONE;
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String raw : entries.split(",")) {
            String host = raw.trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                continue;
            }
            validate(host);
            parsed.add(host);
        }
        return parsed.isEmpty() ? NONE : new NegotiateHosts(parsed);
    }

    private static void validate(String host) {
        // Ordered by severity, so the message names the worst thing wrong rather than the first
        // thing checked: a value carrying CRLF should not be reported as a stray port.
        for (char forbidden : new char[]{'\r', '\n', '\t', ' '}) {
            if (host.indexOf(forbidden) >= 0) {
                throw new IllegalArgumentException("Invalid --krb5-hosts entry '" + host
                        + "': a host name cannot contain spaces or line breaks.");
            }
        }
        // A wildcard would defeat the point of the list. Refused explicitly rather than treated
        // as a literal host name that happens never to match.
        if (host.indexOf('*') >= 0 || host.indexOf('?') >= 0) {
            throw new IllegalArgumentException("Invalid --krb5-hosts entry '" + host
                    + "': wildcards are not accepted. Every host that may receive a Kerberos"
                    + " ticket has to be named.");
        }
        if (host.contains("://") || host.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Invalid --krb5-hosts entry '" + host
                    + "': give a host name such as intranet.example.com, not a URL.");
        }
        if (host.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Invalid --krb5-hosts entry '" + host
                    + "': a Kerberos principal is HTTP/host, so do not include a port.");
        }
    }

    /** True when {@code host} was named. Never true for an empty list. */
    public boolean includes(String host) {
        return host != null && hosts.contains(host.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return hosts.isEmpty();
    }

    /** The hosts, in the order given, for diagnostics. */
    public List<String> hosts() {
        return List.copyOf(hosts);
    }

    @Override
    public String toString() {
        return hosts.isEmpty() ? "none" : String.join(", ", hosts);
    }
}
