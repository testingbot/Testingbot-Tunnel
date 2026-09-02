package com.testingbot.tunnel;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /healthz} and {@code /readyz}, the endpoints Docker HEALTHCHECK and Kubernetes probes
 * consume.
 *
 * <p>The distinction between them is the point: liveness must stay 200 through a reconnect so a
 * supervisor does not kill a process that is recovering on its own, while readiness must drop to
 * 503 so the instance leaves rotation until traffic would actually succeed. {@code --readyfile}
 * can express neither -- it is written once and never removed.
 */
class HealthEndpointsTest {

    private int metricsPort;

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @BeforeEach
    void setUp() throws Exception {
        TunnelMetrics.setTunnelUp(false);
        metricsPort = findFreePort();
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setMetricsPort(metricsPort);
        new InsightServer(app);
        waitForPort(metricsPort);
    }

    @AfterEach
    void tearDown() {
        TunnelMetrics.setTunnelUp(false);
    }

    private static void waitForPort(int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("Insight server did not start on port " + port);
    }

    private static int status(int port, String path) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://127.0.0.1:" + port + path);
            Integer code = client.execute(request, response -> Integer.valueOf(response.getCode()));
            return code.intValue();
        }
    }

    /** @return "status|body" for the given path. */
    private String get(String path) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://127.0.0.1:" + metricsPort + path);
            return client.execute(request, response ->
                    response.getCode() + "|" + EntityUtils.toString(response.getEntity()).trim());
        }
    }

    @Test
    void healthz_is200EvenWhileTheTunnelIsDown() throws Exception {
        assertThat(TunnelMetrics.isTunnelUp()).isFalse();

        assertThat(get("/healthz")).isEqualTo("200|{\"status\":\"ok\"}");
    }

    @Test
    void readyz_is503BeforeTheTunnelIsUp() throws Exception {
        assertThat(get("/readyz")).isEqualTo("503|{\"status\":\"not_ready\"}");
    }

    @Test
    void readyz_is200OnceTheTunnelIsUp() throws Exception {
        TunnelMetrics.setTunnelUp(true);

        assertThat(get("/readyz")).isEqualTo("200|{\"status\":\"ready\"}");
    }

    @Test
    void readyz_dropsBackTo503WhenTheConnectionIsLost() throws Exception {
        TunnelMetrics.setTunnelUp(true);
        assertThat(get("/readyz")).startsWith("200");

        // What CustomConnectionMonitor.connectionLost() does.
        TunnelMetrics.setTunnelUp(false);

        assertThat(get("/readyz")).isEqualTo("503|{\"status\":\"not_ready\"}");
        // ...while liveness stays up, so the process is not killed mid-recovery.
        assertThat(get("/healthz")).startsWith("200");
    }

    @Test
    void healthEndpoints_areReachableWithoutMetricsAuth() throws Exception {
        int port = findFreePort();
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setMetricsPort(port);
        app.setMetricsAuth("user:password");
        new InsightServer(app);
        waitForPort(port);

        // Probes cannot easily carry credentials, so these must not be behind auth...
        assertThat(status(port, "/healthz")).isEqualTo(200);
        assertThat(status(port, "/readyz")).isEqualTo(503);
        // ...but /metrics still is.
        assertThat(status(port, "/metrics")).isEqualTo(401);
    }

    @Test
    void readinessProbe_exitCodeFollowsTheEndpoint() {
        TunnelMetrics.setTunnelUp(true);
        assertThat(ReadinessProbe.probe("127.0.0.1", metricsPort, 2_000)).isZero();

        TunnelMetrics.setTunnelUp(false);
        assertThat(ReadinessProbe.probe("127.0.0.1", metricsPort, 2_000)).isEqualTo(1);
    }

    @Test
    void readinessProbe_reportsNotReadyWhenNothingIsListening() throws Exception {
        int unused = findFreePort();

        // The normal answer before the tunnel has started; must be a clean exit 1, not a hang.
        assertThat(ReadinessProbe.probe("127.0.0.1", unused, 2_000)).isEqualTo(1);
    }
}
