package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests with large headers must be forwarded, not refused.
 *
 * <p>The outbound client was given 32 KiB buffers while the server side kept Jetty's 8 KiB
 * default, so the proxy answered 431 to requests it was perfectly capable of carrying. Long
 * session cookies and large bearer tokens clear 8 KiB routinely in enterprise environments, and
 * the failure looks like the site under test rejecting the request rather than the tunnel.
 */
class LargeHeaderTest {

    private ServerSocket origin;
    private ExecutorService pool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int originPort;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @BeforeEach
    void setUp() throws Exception {
        origin = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        originPort = origin.getLocalPort();
        pool = Executors.newCachedThreadPool();
        acceptor = new Thread(() -> {
            while (!origin.isClosed()) {
                try {
                    Socket accepted = origin.accept();
                    pool.submit(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8));
                            in.readLine();
                            int cookieBytes = 0;
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                if (line.startsWith("X-Big-")) {
                                    cookieBytes += line.length();
                                }
                            }
                            byte[] body = ("SAW=" + cookieBytes).getBytes(StandardCharsets.UTF_8);
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            out.write(body);
                            out.flush();
                        } catch (Exception ignored) {
                            // client went away
                        }
                    });
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
        if (origin != null && !origin.isClosed()) {
            origin.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
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

    /** Builds a request carrying roughly {@code totalBytes} of custom headers. */
    private String requestWithHeaders(int totalBytes) {
        StringBuilder request = new StringBuilder()
                .append("GET http://127.0.0.1:").append(originPort).append("/ HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(originPort).append("\r\n");
        String value = "x".repeat(900);
        for (int i = 0; request.length() < totalBytes; i++) {
            request.append("X-Big-").append(i).append(": ").append(value).append("\r\n");
        }
        return request.append("Connection: close\r\n\r\n").toString();
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
    void headersLargerThanJettysDefaultAreStillForwarded() throws Exception {
        // Comfortably past the 8 KiB default, comfortably inside the 32 KiB allowance.
        String response = send(requestWithHeaders(16 * 1024));

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(response).contains("SAW=");
    }

    @Test
    void anOrdinaryRequestIsUnaffected() throws Exception {
        String response = send("GET http://127.0.0.1:" + originPort + "/ HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 200");
    }

    @Test
    void headersBeyondTheAllowanceAreStillRefused() throws Exception {
        // The limit is raised, not removed: an unbounded allowance is a memory attack.
        String response = send(requestWithHeaders(64 * 1024));

        assertThat(response)
                .as("refused for being too large, not failed for some other reason")
                .startsWith("HTTP/1.1 431");
    }
}
