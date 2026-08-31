package com.testingbot.tunnel.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * Resolves hostnames against a DNS server named on the command line rather than the
 * platform resolver.
 *
 * <p>Needed because the JDK removed its pluggable nameservice SPI in Java 9 and only
 * reintroduced a supported one in Java 18 ({@code InetAddressResolverProvider}, JEP 418),
 * while this project targets 17. Setting the old {@code sun.net.spi.nameservice.*}
 * properties, as this option used to, has no effect on any JDK we support.
 *
 * <p>Falls back to the platform resolver when no configured server can answer, so a
 * misconfigured {@code --dns} degrades to normal behaviour instead of taking the tunnel down.
 *
 * <p>Several servers may be given. By default the first is primary and the rest are tried in
 * order when it does not answer, which is what an internal resolver with a standby looks like.
 * {@code --dns-round-robin} spreads queries across them instead, for a pool where every member
 * is equal and one of them being slow should not slow everything.
 */
public final class CustomDnsResolver {

    private static final Logger LOG = Logger.getLogger(CustomDnsResolver.class.getName());
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final String server;
    private final List<Resolver> resolvers;
    private final boolean roundRobin;
    /** Only read and incremented under round robin, where exact fairness does not matter. */
    private final java.util.concurrent.atomic.AtomicInteger next =
            new java.util.concurrent.atomic.AtomicInteger();

    private CustomDnsResolver(String server, List<Resolver> resolvers, boolean roundRobin) {
        this.server = server;
        this.resolvers = List.copyOf(resolvers);
        this.roundRobin = roundRobin;
    }

    /**
     * @param server one or more DNS servers as {@code host} or {@code host:port}, comma separated
     * @return a resolver, or null if {@code server} is blank or none of the entries are usable
     */
    public static CustomDnsResolver create(String server) {
        return create(server, DEFAULT_QUERY_TIMEOUT, false);
    }

    /**
     * @param server     comma-separated DNS servers, each {@code host} or {@code host:port}
     * @param timeout    per-query timeout for these servers
     * @param roundRobin spread queries across them rather than treating the first as primary
     * @return a resolver, or null if nothing usable was configured -- the caller then keeps the
     *         platform resolver, which is better than failing every lookup
     */
    public static CustomDnsResolver create(String server, Duration timeout, boolean roundRobin) {
        if (server == null || server.trim().isEmpty()) {
            return null;
        }
        List<Resolver> resolvers = new ArrayList<>();
        List<String> usable = new ArrayList<>();
        for (String entry : server.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Resolver resolver = build(trimmed, timeout);
            if (resolver != null) {
                resolvers.add(resolver);
                usable.add(trimmed);
            }
        }
        if (resolvers.isEmpty()) {
            return null;
        }
        LOG.log(Level.INFO, "Using custom DNS server(s) {0}{1}",
                new Object[]{String.join(", ", usable), roundRobin ? " (round robin)" : ""});
        return new CustomDnsResolver(String.join(",", usable), resolvers, roundRobin);
    }

    /** One server, or null when it cannot be used; the others still can be. */
    private static Resolver build(String value, Duration timeout) {
        try {
            // SimpleResolver(String) takes a bare hostname, so host:port has to be split here.
            // An IPv6 literal must be bracketed to be distinguishable from its own colons.
            String host = value;
            int port = -1;
            int colon = value.lastIndexOf(':');
            if (value.startsWith("[")) {
                int close = value.indexOf(']');
                if (close > 0) {
                    host = value.substring(1, close);
                    if (close + 2 < value.length() && value.charAt(close + 1) == ':') {
                        port = Integer.parseInt(value.substring(close + 2));
                    }
                }
            } else if (colon > 0 && value.indexOf(':') == colon) {
                host = value.substring(0, colon);
                port = Integer.parseInt(value.substring(colon + 1));
            }

            SimpleResolver simple = new SimpleResolver(host);
            if (port > 0) {
                simple.setPort(port);
            }
            simple.setTimeout(timeout);
            return simple;
        } catch (NumberFormatException ex) {
            LOG.log(Level.WARNING,
                "Invalid port in DNS server ''{0}''; falling back to the system resolver", value);
            return null;
        } catch (UnknownHostException ex) {
            LOG.log(Level.WARNING,
                "Could not use DNS server ''{0}'' ({1}); falling back to the system resolver",
                new Object[]{value, ex.getMessage()});
            return null;
        }
    }

    /** @return the configured servers, comma separated, for diagnostics */
    public String getServer() {
        return server;
    }

    /** How many servers are actually in use; the rest were unusable and dropped. */
    public int serverCount() {
        return resolvers.size();
    }

    /**
     * The servers to try for one lookup, in the order they should be tried.
     *
     * <p>Round robin rotates the starting point rather than picking one and stopping: spreading
     * load is the goal, but a query still has to fall through to the others when the one it
     * started on does not answer.
     */
    private List<Resolver> attemptOrder() {
        if (!roundRobin || resolvers.size() == 1) {
            return resolvers;
        }
        int start = Math.floorMod(next.getAndIncrement(), resolvers.size());
        List<Resolver> ordered = new ArrayList<>(resolvers.size());
        for (int i = 0; i < resolvers.size(); i++) {
            ordered.add(resolvers.get((start + i) % resolvers.size()));
        }
        return ordered;
    }

    /**
     * Resolves {@code host} to one or more addresses.
     *
     * <p>An IP literal is returned as-is without a query. When the configured server returns
     * nothing, the platform resolver is tried before giving up.
     */
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (host == null || host.isEmpty()) {
            throw new UnknownHostException("empty host");
        }
        if (isIpLiteral(host)) {
            return InetAddress.getAllByName(host);
        }

        List<InetAddress> addresses = new ArrayList<>();
        // Each server is asked for A and then AAAA before moving on, so a name that only has
        // an AAAA record is not treated as unanswered and chased across every server first.
        for (Resolver resolver : attemptOrder()) {
            lookup(resolver, host, Type.A, addresses);
            if (addresses.isEmpty()) {
                lookup(resolver, host, Type.AAAA, addresses);
            }
            if (!addresses.isEmpty()) {
                break;
            }
        }

        if (addresses.isEmpty()) {
            LOG.log(Level.FINE, "DNS server {0} returned no records for {1}; using system resolver",
                    new Object[]{server, host});
            return InetAddress.getAllByName(host);
        }
        return addresses.toArray(new InetAddress[0]);
    }

    private void lookup(Resolver resolver, String host, int type, List<InetAddress> out) {
        try {
            Lookup lookup = new Lookup(host, type);
            lookup.setResolver(resolver);
            // Each tunnel process is short-lived relative to TTLs and the JVM already caches;
            // dnsjava's own cache would only add a second, harder-to-reason-about layer.
            lookup.setCache(null);
            Record[] records = lookup.run();
            if (records == null) {
                return;
            }
            for (Record record : records) {
                if (record instanceof ARecord) {
                    out.add(((ARecord) record).getAddress());
                } else if (record instanceof AAAARecord) {
                    out.add(((AAAARecord) record).getAddress());
                }
            }
        } catch (TextParseException ex) {
            LOG.log(Level.FINE, "Not a resolvable name: {0}", host);
        }
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;                       // IPv6 literal
        }
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
    }
}
