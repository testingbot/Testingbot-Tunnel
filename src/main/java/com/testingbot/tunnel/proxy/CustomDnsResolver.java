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
 * <p>Falls back to the platform resolver when the configured server cannot answer, so a
 * misconfigured {@code --dns} degrades to normal behaviour instead of taking the tunnel down.
 */
public final class CustomDnsResolver {

    private static final Logger LOG = Logger.getLogger(CustomDnsResolver.class.getName());
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final String server;
    private final Resolver resolver;

    private CustomDnsResolver(String server, Resolver resolver) {
        this.server = server;
        this.resolver = resolver;
    }

    /**
     * @param server DNS server as {@code host} or {@code host:port}
     * @return a resolver, or null if {@code server} is blank or unusable
     */
    public static CustomDnsResolver create(String server) {
        if (server == null || server.trim().isEmpty()) {
            return null;
        }
        String value = server.trim();
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
            simple.setTimeout(QUERY_TIMEOUT);
            LOG.log(Level.INFO, "Using custom DNS server {0}", value);
            return new CustomDnsResolver(value, simple);
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

    /** @return the configured server, for diagnostics */
    public String getServer() {
        return server;
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
        lookup(host, Type.A, addresses);
        if (addresses.isEmpty()) {
            lookup(host, Type.AAAA, addresses);
        }

        if (addresses.isEmpty()) {
            LOG.log(Level.FINE, "DNS server {0} returned no records for {1}; using system resolver",
                    new Object[]{server, host});
            return InetAddress.getAllByName(host);
        }
        return addresses.toArray(new InetAddress[0]);
    }

    private void lookup(String host, int type, List<InetAddress> out) {
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
