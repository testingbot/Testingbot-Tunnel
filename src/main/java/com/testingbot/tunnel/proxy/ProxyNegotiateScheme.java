package com.testingbot.tunnel.proxy;

import java.security.Principal;
import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;

/** Adapts the tunnel's Kerberos credentials to HttpClient's proxy challenge handling. */
public final class ProxyNegotiateScheme implements AuthScheme {
    private final ProxySpec proxy;
    private final ProxyAuthenticator authenticator;
    private boolean complete;

    public ProxyNegotiateScheme(ProxySpec proxy, ProxyAuthenticator authenticator) {
        this.proxy = proxy;
        this.authenticator = authenticator;
    }

    @Override
    public String getName() {
        return "Negotiate";
    }

    @Override
    public boolean isConnectionBased() {
        return false;
    }

    @Override
    public void processChallenge(AuthChallenge challenge, HttpContext context) {
        // The shared authenticator emits a single Kerberos token. A second rejection must
        // terminate authentication rather than loop or fall back to Basic credentials.
        complete = true;
    }

    @Override
    public boolean isChallengeComplete() {
        return complete;
    }

    @Override
    public String getRealm() {
        return null;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public boolean isResponseReady(HttpHost host, CredentialsProvider credentials, HttpContext context) {
        return host != null && "http".equalsIgnoreCase(host.getSchemeName())
                && proxy.getHost().equalsIgnoreCase(host.getHostName())
                && proxy.getPort() == host.getPort();
    }

    @Override
    public String generateAuthResponse(HttpHost host, HttpRequest request, HttpContext context)
            throws AuthenticationException {
        if (!isResponseReady(host, null, context)) {
            throw new AuthenticationException("Negotiate credentials are scoped to the configured proxy");
        }
        String value = authenticator.authorizationValue(proxy.getHost());
        if (value == null) {
            throw new AuthenticationException("Could not obtain a Negotiate token for the control proxy");
        }
        return value;
    }
}
