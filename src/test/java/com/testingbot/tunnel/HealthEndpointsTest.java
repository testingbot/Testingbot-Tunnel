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

    private InsightServer insightServer;
    /** The App the endpoints answer for: readiness is per tunnel, not per process. */
    private App app;

    @BeforeEach
    void setUp() throws Exception {
        TunnelMetrics.setTunnelUp(false);
        metricsPort = findFreePort();
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setMetricsPort(metricsPort);
        insightServer = new InsightServer(app);
        Await.serverOn(metricsPort);
    }

    @AfterEach
    void tearDown() {
        TunnelMetrics.setTunnelUp(false);
        // The server was constructed and dropped, so every test in this class left a Jetty
        // server and a bound port behind for the rest of the JVM's life. Surefire forks per
        // class here, which is the only reason it did not accumulate across the whole suite.
        if (insightServer != null) {
            insightServer.stop();
            insightServer = null;
        }
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
        // Through the App, which is what the tunnel itself does. The gauge is mirrored from
        // here, not read by the endpoint: two tunnels in one process each answer for their own.
        app.setReady(true);

        assertThat(get("/readyz")).isEqualTo("200|{\"status\":\"ready\"}");
    }

    @Test
    void readyz_dropsBackTo503WhenTheConnectionIsLost() throws Exception {
        app.setReady(true);
        assertThat(get("/readyz")).startsWith("200");

        // What CustomConnectionMonitor.connectionLost() does, via ReconnectHost.
        app.setReady(false);

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
        // A second server, so it needs stopping too: tearDown only knows about the one from
        // setUp. Left running, it held this port for the rest of the JVM.
        InsightServer authed = new InsightServer(app);
        try {
            Await.serverOn(port);

            // Probes cannot easily carry credentials, so these must not be behind auth...
            assertThat(status(port, "/healthz")).isEqualTo(200);
            assertThat(status(port, "/readyz")).isEqualTo(503);
            // ...but /metrics still is.
            assertThat(status(port, "/metrics")).isEqualTo(401);
        } finally {
            authed.stop();
        }
    }

    @Test
    void readinessProbe_exitCodeFollowsTheEndpoint() {
        app.setReady(true);
        assertThat(ReadinessProbe.probe("127.0.0.1", metricsPort, 2_000)).isZero();

        app.setReady(false);
        assertThat(ReadinessProbe.probe("127.0.0.1", metricsPort, 2_000)).isEqualTo(1);
    }

    @Test
    void readinessProbe_reportsNotReadyWhenNothingIsListening() throws Exception {
        int unused = findFreePort();

        // The normal answer before the tunnel has started; must be a clean exit 1, not a hang.
        assertThat(ReadinessProbe.probe("127.0.0.1", unused, 2_000)).isEqualTo(1);
    }
}
