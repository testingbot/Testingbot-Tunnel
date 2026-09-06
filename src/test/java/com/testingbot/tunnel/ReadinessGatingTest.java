package com.testingbot.tunnel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness has to mean the tunnel is actually forwarding.
 *
 * <p>{@code startProxies()} runs three checks -- the Selenium relay reaching the hub, the reverse
 * forward reaching the local proxy, and the proxy reaching the internet -- and returned whether
 * they passed. The caller then set the gauge to {@code true} regardless and the ready file was
 * written from inside {@code startProxies()} on every path, so a tunnel that had just failed all
 * three still answered 200 on {@code /readyz} and still touched {@code --readyfile}. Container
 * probes and supervisors were being told to send work to a tunnel that could carry none.
 *
 * <p>{@link HealthEndpointsTest} covers the endpoints themselves, but sets the gauge by hand --
 * which is why it could not see this. These drive the real startup path instead.
 */
class ReadinessGatingTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TunnelMetrics.setTunnelUp(false);
    }

    @AfterEach
    void tearDown() {
        TunnelMetrics.setTunnelUp(false);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * An App whose local listeners come up but whose forwarding cannot work: there is no SSH
     * tunnel behind the Selenium relay, so its self-test fails exactly as it would for a
     * customer whose tunnel did not establish.
     */
    private App appWithNoWorkingTunnel() throws IOException {
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setSeleniumPort(freePort());
        // --no-proxy so the test does not need to bind a proxy port or reach the internet; the
        // forwarding check alone is enough to make the startup unhealthy.
        app.setNoProxy(true);
        return app;
    }

    @Test
    void aFailedStartupIsNotReported() throws Exception {
        App app = appWithNoWorkingTunnel();
        try {
            assertThat(app.startProxies())
                    .as("the forwarding self-test cannot pass without a tunnel behind it")
                    .isFalse();
        } finally {
            app.stop();
        }
    }

    @Test
    void aFailedStartupWritesNoReadyFile() throws Exception {
        Path readyFile = tempDir.resolve("tunnel.ready");
        App app = appWithNoWorkingTunnel();
        app.setReadyFile(readyFile.toString());

        try {
            assertThat(app.startProxies()).isFalse();

            // The regression: this file was written at the end of startProxies() whatever the
            // checks above it had found, so anything waiting on it started sending work.
            assertThat(Files.exists(readyFile))
                    .as("--readyfile means ready, not merely 'startup was attempted'")
                    .isFalse();
        } finally {
            app.stop();
        }
    }

    @Test
    void aFailedStartupLeavesTheTunnelNotReady() throws Exception {
        App app = appWithNoWorkingTunnel();
        try {
            app.startProxies();

            assertThat(TunnelMetrics.isTunnelUp())
                    .as("/readyz must not answer 200 for a tunnel that failed its self-test")
                    .isFalse();
        } finally {
            app.stop();
        }
    }

    @Test
    void theReadyFileIsStillWrittenOnTheHealthyPath() throws Exception {
        // The other half: gating must not turn into never writing it at all.
        Path readyFile = tempDir.resolve("tunnel.ready");
        App app = new App();
        app.setReadyFile(readyFile.toString());

        app.writeReadyFile();

        assertThat(Files.exists(readyFile)).isTrue();
        assertThat(Files.readString(readyFile)).contains("Ready");
    }

    @Test
    void writingTheReadyFileTwiceTouchesRatherThanFails() throws Exception {
        Path readyFile = tempDir.resolve("tunnel.ready");
        App app = new App();
        app.setReadyFile(readyFile.toString());

        app.writeReadyFile();
        app.writeReadyFile();

        assertThat(Files.exists(readyFile)).isTrue();
    }
}
