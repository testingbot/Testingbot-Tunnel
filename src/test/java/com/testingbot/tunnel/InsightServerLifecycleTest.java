package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The metrics server must be released like everything else.
 *
 * <p>Its reference used to be discarded the moment it was constructed, so nothing could stop it.
 * A tunnel rebuild -- which the reconnect monitor performs after enough failures -- called
 * startInsightServer() again, and the second server simply failed to bind. Metrics kept coming
 * from the old instance while the "metrics" connection listener had been repointed at the dead
 * one, and an embedder starting a tunnel per job leaked a server and a bound port each time.
 */
class InsightServerLifecycleTest {

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    private static boolean isListening(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException notListening) {
            return false;
        }
    }

    private static App app(int metricsPort) {
        App app = new App();
        app.setClientKey("k");
        app.setClientSecret("s");
        app.setMetricsPort(metricsPort);
        return app;
    }

    @Test
    void appStopReleasesTheMetricsPort() throws Exception {
        int port = findFreePort();
        App app = app(port);
        app.startInsightServer();
        assertThat(isListening(port)).isTrue();

        app.stop();

        assertThat(isListening(port)).isFalse();
    }

    @Test
    void startingAgainReplacesTheServerRatherThanFailingToBind() throws Exception {
        // What a tunnel rebuild does. The second server used to collide with the first.
        int port = findFreePort();
        App app = app(port);
        try {
            app.startInsightServer();
            app.startInsightServer();
            app.startInsightServer();

            assertThat(isListening(port))
                    .as("the newest server should be serving")
                    .isTrue();
        } finally {
            app.stop();
        }
        assertThat(isListening(port)).isFalse();
    }

    @Test
    void theReplacementServerActuallyAnswers() throws Exception {
        // Binding is not enough: metrics must still be served after a rebuild.
        int port = findFreePort();
        App app = app(port);
        try {
            app.startInsightServer();
            app.startInsightServer();

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(
                        "GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);

                assertThat(response).contains("200");
            }
        } finally {
            app.stop();
        }
    }

    @Test
    void stoppingWithoutAMetricsServerIsSafe() {
        assertThatCode(() -> app(0).stop()).doesNotThrowAnyException();
    }
}
