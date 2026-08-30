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

    private ProxyAuthenticator(Scheme scheme, String basicToken, SpnegoClient spnego) {
        this.scheme = scheme;
        this.basicToken = basicToken;
        this.spnego = spnego;
    }

    /** No upstream authentication configured. */
    public static ProxyAuthenticator none() {
        return new ProxyAuthenticator(Scheme.BASIC, null, null);
    }

    public static ProxyAuthenticator basic(String userPassword) {
        if (userPassword == null || userPassword.isEmpty()) {
            return none();
        }
        return new ProxyAuthenticator(Scheme.BASIC,
                Base64.getEncoder().encodeToString(userPassword.getBytes(StandardCharsets.UTF_8)),
                null);
    }

    public static ProxyAuthenticator negotiate(SpnegoClient spnego) {
        return new ProxyAuthenticator(Scheme.NEGOTIATE, null, spnego);
    }

    /** Chooses from the parsed command line. */
    public static ProxyAuthenticator create(Scheme scheme, String userPassword,
                                            String servicePrincipal, Path keyTab, String principal) {
        if (scheme == Scheme.NEGOTIATE) {
            return negotiate(new SpnegoClient(servicePrincipal, keyTab, principal));
        }
        return basic(userPassword);
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

    /** The SPN that would be used, for diagnostics. Null unless Negotiate is configured. */
    public String servicePrincipalFor(String proxyHost) {
        return spnego == null ? null : spnego.servicePrincipalFor(proxyHost);
    }
}
