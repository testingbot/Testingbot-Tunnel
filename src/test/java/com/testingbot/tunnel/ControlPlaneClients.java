package com.testingbot.tunnel;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;

/**
 * Test-only access to the control-plane HTTP client.
 *
 * <p>{@code Api.controlPlaneBuilder} is package-private, and the tests that need to drive it --
 * an upstream proxy demanding Kerberos, say -- live elsewhere. This exposes it without widening
 * the production API.
 */
public final class ControlPlaneClients {

    private ControlPlaneClients() {
    }

    /** The client the API calls, {@code --doctor} and the startup self-test all use. */
    public static HttpClientBuilder forApp(App app) {
        return Api.controlPlaneBuilder(app);
    }
}
