package com.testingbot.tunnel.proxy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the {@code Proxy-Authorization} value for the upstream proxy.
 *
 * <p>Egress supported Basic only. Enterprise proxies increasingly require Negotiate/SPNEGO, and
 * a tunnel that cannot satisfy one is simply unusable on those networks.
 *
 * <p>Scope is deliberately the upstream proxy alone. Per-site authentication is {@code --auth}
 * and stays Basic.
 *
 * <p>Used by all three egress paths -- the CONNECT tunnel, the plain-HTTP client, and the SSH
 * bootstrap -- so a proxy that accepts one accepts all of them.
 */
public final class ProxyAuthenticator {

    private static final Logger LOG = Logger.getLogger(ProxyAuthenticator.class.getName());

    public enum Scheme {
        BASIC,
        NEGOTIATE;

        public static Scheme parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                return BASIC;
            }
            return Scheme.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private final Scheme scheme;
    private final String basicToken;
    private final SpnegoClient spnego;
    /**
     * The one host these credentials belong to, or null for "any".
     *
     * <p>They were issued for a named proxy, and a proxy the client happens to dial is not
     * automatically that proxy. Nothing bound them before, so
     * {@code authorizationValue(proxyHost)} ignored its argument and handed the token to
     * whoever was being connected to -- and with {@code --pac-local} the PAC file chooses that
     * host per destination.
     *
     * <p>Matched on host, not host:port, because that is all the dial sites know at the point
     * they ask. A different port on the same proxy host is the same proxy in every deployment
     * this has, and narrowing further would refuse credentials to a proxy that should have them.
     */
    private final String authorizedHost;

    private ProxyAuthenticator(Scheme scheme, String basicToken, SpnegoClient spnego,
                               String authorizedHost) {
        this.scheme = scheme;
        this.basicToken = basicToken;
        this.spnego = spnego;
        this.authorizedHost = authorizedHost == null || authorizedHost.isBlank()
                ? null
                : authorizedHost.trim().toLowerCase(Locale.ROOT);
    }

    /** No upstream authentication configured. */
    public static ProxyAuthenticator none() {
        return new ProxyAuthenticator(Scheme.BASIC, null, null, null);
    }

    /**
     * @param authorizedHost the proxy these credentials are for; null means any host, which is
     *                       only appropriate in tests
     */
    public static ProxyAuthenticator basic(String userPassword, String authorizedHost) {
        if (userPassword == null || userPassword.isEmpty()) {
            return none();
        }
        return new ProxyAuthenticator(Scheme.BASIC,
                Base64.getEncoder().encodeToString(userPassword.getBytes(StandardCharsets.UTF_8)),
                null, authorizedHost);
    }

    public static ProxyAuthenticator negotiate(SpnegoClient spnego, String authorizedHost) {
        return new ProxyAuthenticator(Scheme.NEGOTIATE, null, spnego, authorizedHost);
    }

    /** Chooses from the parsed command line. */
    public static ProxyAuthenticator create(Scheme scheme, String userPassword,
                                            String servicePrincipal, Path keyTab, String principal,
                                            String authorizedHost) {
        if (scheme == Scheme.NEGOTIATE) {
            return negotiate(new SpnegoClient(servicePrincipal, keyTab, principal), authorizedHost);
        }
        return basic(userPassword, authorizedHost);
    }

    public Scheme getScheme() {
        return scheme;
    }

    public boolean isNegotiate() {
        return scheme == Scheme.NEGOTIATE;
    }

    /** True when there is anything to send at all. */
    public boolean isConfigured() {
        return basicToken != null || spnego != null;
    }

    /**
     * @return the full header value, e.g. {@code Negotiate YIIC...} or {@code Basic dXNlcg==},
     *         or null when nothing is configured or a token could not be obtained
     */
    public String authorizationValue(String proxyHost) {
        if (!isAuthorizedPeer(proxyHost)) {
            // Logged rather than silent: the request will come back as a 407 from a proxy the
            // operator did not expect to be talking to, and "no credentials were sent, and why"
            // is the only thing that makes that traceable.
            LOG.log(Level.WARNING,
                    "Not sending upstream proxy credentials to {0}: they belong to {1}."
                    + " A proxy chosen by --pac-local is not the proxy they were issued for.",
                    new Object[]{proxyHost,
                                 authorizedHost == null
                                         ? "no proxy, because --proxy is unset or unparseable"
                                         : authorizedHost});
            return null;
        }
        if (scheme == Scheme.NEGOTIATE) {
            if (spnego == null) {
                return null;
            }
            try {
                return "Negotiate " + spnego.initialToken(proxyHost);
            } catch (Exception ex) {
                // Failing the dial with a Kerberos stack trace helps nobody; --doctor exists to
                // explain why, and the request will surface as a 407 from the proxy.
                LOG.log(Level.WARNING,
                        "Could not obtain a Kerberos token for the upstream proxy (SPN {0}): {1}."
                        + " Run --doctor for details.",
                        new Object[]{spnego.servicePrincipalFor(proxyHost), ex.getMessage()});
                return null;
            }
        }
        return basicToken == null ? null : "Basic " + basicToken;
    }

    /**
     * True when {@code proxyHost} is the peer these credentials were issued for.
     *
     * <p>An unconfigured authenticator has nothing to give away, so it is not restricted.
     * Configured credentials with no named peer are authorized to <em>nobody</em>: that state is
     * reachable in production -- {@code --proxy-userpwd} with no parseable {@code --proxy} --
     * and it is precisely the case {@code --pac-local} then picks the proxy for, so treating it
     * as "any host" would have left the leak open in the one configuration where the PAC file is
     * the only source of a proxy. It also matches the SOCKS gate, which refuses when there is no
     * configured proxy to compare against.
     */
    public boolean isAuthorizedPeer(String proxyHost) {
        if (!isConfigured()) {
            return true;
        }
        return authorizedHost != null && proxyHost != null
                && authorizedHost.equals(proxyHost.trim().toLowerCase(Locale.ROOT));
    }

    /** The SPN that would be used, for diagnostics. Null unless Negotiate is configured. */
    public String servicePrincipalFor(String proxyHost) {
        return spnego == null ? null : spnego.servicePrincipalFor(proxyHost);
    }
}
