package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that names the proxy's own address must be refused, not forwarded.
 *
 * <p>Without this the request re-enters the handler, gaining a Via and a set of X-Forwarded-*
 * headers each time, until the accumulated headers overflow the buffer. Measured before the fix:
 * a single request produced 35 handler invocations and left 34 sockets open after the client had
 * already gone. Anything that probes the proxy port -- a scanner, a misconfigured health check --
 * is a 35x amplifier.
 */
class ProxyLoopTest {

    private HttpProxy httpProxy;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Statistics.reset();
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        Statistics.reset();
    }

    private static void waitForPort(int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("Proxy did not start on port " + port);
    }

    private String send(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anAbsoluteFormRequestNamingTheProxyIsRefusedOnce() throws Exception {
        String response = send("GET http://127.0.0.1:" + proxyPort + "/ HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + proxyPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response).contains("508");
        assertThat(response).contains("loop-detected");
        Thread.sleep(300);
        assertThat(Statistics.getNumberOfRequests())
                .as("the request must not re-enter the handler")
                .isEqualTo(1);
    }

    @Test
    void aRequestNamingLocalhostOnTheProxyPortIsAlsoRefused() throws Exception {
        // Same address by another name; matching only the literal 127.0.0.1 would miss it.
        String response = send("GET http://localhost:" + proxyPort + "/ HTTP/1.1\r\n"
                + "Host: localhost:" + proxyPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response).contains("508");
    }

    @Test
    void aViaHeaderNamingThisProxyIsTreatedAsALoop() throws Exception {
        // What RFC 9110 defines Via for: it catches a loop that arrives by some other route,
        // where the destination is not our own address.
        String viaHost = java.net.InetAddress.getLocalHost().getHostName();
        String response = send("GET http://example.invalid/ HTTP/1.1\r\n"
                + "Host: example.invalid\r\n"
                + "Via: 1.1 " + viaHost + "\r\nConnection: close\r\n\r\n");

        assertThat(response).contains("508");
    }

    @Test
    void anOrdinaryRequestOnADifferentPortIsNotMistakenForALoop() throws Exception {
        // The check must not refuse legitimate traffic: same host, different port.
        int otherPort = findFreePort();

        String response = send("GET http://127.0.0.1:" + otherPort + "/ HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + otherPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response).doesNotContain("508");
    }

    @Test
    void anExternalHostIsNotMistakenForALoop() throws Exception {
        String response = send("GET http://example.invalid/ HTTP/1.1\r\n"
                + "Host: example.invalid\r\nConnection: close\r\n\r\n");

        assertThat(response).doesNotContain("508");
    }
}
