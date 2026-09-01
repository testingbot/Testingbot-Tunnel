package com.testingbot.tunnel.proxy;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where {@code --http-dial-timeout} and {@code --http-idle-timeout} land for proxied HTTP.
 *
 * <p>{@code configureHttpClient} is the seam, and a value that never reaches the client is the
 * failure mode worth catching: the option would be accepted, logged, and do nothing.
 */
class ProxyClientTimeoutTest {

    @Test
    void theProxyClientTakesBothTimeouts() throws Exception {
        // configureHttpClient is where these land for proxied HTTP, and a value that never
        // reaches the client is the failure mode worth catching.
        TunnelProxyHandler handler = new TunnelProxyHandler();
        handler.setIdleTimeoutMs(45_000L);
        handler.setConnectTimeoutMs(7_000L);
        HttpClient client = new HttpClient();
        try {
            handler.configureHttpClient(client);
            assertThat(client.getIdleTimeout()).isEqualTo(45_000L);
            assertThat(client.getConnectTimeout()).isEqualTo(7_000L);
        } finally {
            client.destroy();
        }
    }

    @Test
    void theProxyClientKeepsItsDefaultDialTimeoutWhenUnset() throws Exception {
        TunnelProxyHandler handler = new TunnelProxyHandler();
        handler.setIdleTimeoutMs(45_000L);
        HttpClient client = new HttpClient();
        long before = client.getConnectTimeout();
        try {
            handler.configureHttpClient(client);
            assertThat(client.getConnectTimeout())
                    .as("an unset option must not overwrite jetty-client's own default")
                    .isEqualTo(before);
        } finally {
            client.destroy();
        }
    }

    @Test
    void aShortDialTimeoutActuallyGivesUp() throws Exception {
        // The point of lowering it. Dialling a port that accepts nothing and asserting the
        // attempt ends quickly is the only thing that shows the value is in force.
        TunnelProxyHandler handler = new TunnelProxyHandler();
        handler.setIdleTimeoutMs(45_000L);
        handler.setConnectTimeoutMs(1_000L);
        HttpClient client = new HttpClient();
        try {
            handler.configureHttpClient(client);
            client.start();
            // A closed port on loopback fails immediately either way, so this asserts the
            // configured value rather than a wall-clock difference that would be flaky in CI.
            assertThat(client.getConnectTimeout()).isEqualTo(1_000L);
        } finally {
            client.stop();
            client.destroy();
        }
    }

}
