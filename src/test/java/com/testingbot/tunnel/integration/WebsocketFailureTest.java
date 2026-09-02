package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a client is told when a WebSocket upgrade cannot be dialled.
 *
 * <p>WebsocketHandler never overrode {@code onConnectFailure}, so a TCP-level failure went to
 * Jetty's default, which sets {@code Content-Length: 0} and then writes an error body. The
 * client got {@code 500 java.io.IOException: written 354 > 0 content-length} — an error about
 * Jetty's own bookkeeping — where the CONNECT path for the same unreachable host answers 502
 * with {@code X-TestingBot-Error: connection-refused}. "The service behind the tunnel is down"
 * and "the tunnel is broken" were indistinguishable.
 */
class WebsocketFailureTest {

    private HttpProxy httpProxy;
    private int proxyPort;

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
    }

    private void startProxy() throws Exception {
        proxyPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    /** A port nothing listens on, so the dial is refused rather than timing out. */
    private static int deadPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String request(String raw) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            // Read to the end of the headers, not to EOF: an error response may be sent with
            // the connection kept alive, and reading to EOF would then block until the socket
            // timeout and report a hang where there was an answer.
            StringBuilder response = new StringBuilder();
            while (response.indexOf("\r\n\r\n") < 0) {
                int b = socket.getInputStream().read();
                if (b < 0) {
                    break;
                }
                response.append((char) b);
            }
            return response.toString();
        }
    }

    @Test
    void anUndialableUpgradeIsReportedTheSameWayConnectReportsIt() throws Exception {
        startProxy();
        int dead = deadPort();

        String upgrade = request("GET http://127.0.0.1:" + dead + "/ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + dead + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n");

        assertThat(upgrade)
                .as("a refused dial is not a server error in this proxy")
                .doesNotContain("500");
        assertThat(upgrade)
                .as("Jetty's own content-length bookkeeping must not reach the client")
                .doesNotContain("content-length violation")
                .doesNotContain("written 354");
        assertThat(upgrade).contains("502");
        assertThat(upgrade).contains("connection-refused");
    }

    @Test
    void connectAndTheUpgradeAgreeOnTheSameUnreachableHost() throws Exception {
        // The comparison that makes the point: two ways of asking for the same dead host should
        // not produce two different diagnoses.
        startProxy();
        int dead = deadPort();

        String connect = request("CONNECT 127.0.0.1:" + dead + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + dead + "\r\n\r\n");
        String upgrade = request("GET http://127.0.0.1:" + dead + "/ws HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + dead + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n");

        assertThat(connect).contains("502").contains("connection-refused");
        assertThat(upgrade).contains("502").contains("connection-refused");
    }
}
