package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * Paths that Jetty 12 calls "ambiguous" must still be relayed by the proxy, verbatim.
 *
 * <p>A forward proxy does not resolve paths against a file system, so the ambiguity Jetty
 * guards against (which segment does {@code //} or {@code %2F} address?) is the origin's
 * question, not ours. Jetty 12's default {@code UriCompliance} answered it for the origin by
 * refusing the request with 400 "Ambiguous URI empty segment" before it was ever forwarded.
 *
 * <p>That broke TestCafe outright: its proxy URLs embed the page URL in the path, e.g.
 * {@code http://10.0.0.5:40705/sessionId*windowId/http://example.com/}, so every page after
 * the idle page failed and TestCafe reported "browser disconnected" ~2 minutes later.
 *
 * <p>A path starting with {@code //} is not covered: jetty-client's {@code Request.path()}
 * reads it as an authority, which is a separate problem.
 */
class AmbiguousPathTest {

    private ServerSocket origin;
    private ExecutorService originPool;
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
        originPool = Executors.newCachedThreadPool();

        // Echoes the request line back so the test can assert on exactly what was forwarded.
        acceptor = new Thread(() -> {
            while (!origin.isClosed()) {
                try {
                    Socket accepted = origin.accept();
                    originPool.submit(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            String requestLine = in.readLine();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                // drain headers
                            }
                            byte[] body = ("SAW=" + requestLine).getBytes(StandardCharsets.UTF_8);
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            out.write(body);
                            out.flush();
                        } catch (Exception ignored) {
                            // client went away; nothing useful to do
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
        if (originPool != null) {
            originPool.shutdownNow();
            originPool.awaitTermination(5, TimeUnit.SECONDS);
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

    /** Sends an absolute-form request through the proxy and returns "status\nbody". */
    private String proxyGet(String pathQuery) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://127.0.0.1:" + originPort + pathQuery + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            StringBuilder rest = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                rest.append(line).append('\n');
            }
            return status + "\n" + rest;
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/cms9pQ8*windowId/http://devexpress.github.io/testcafe/example", // TestCafe proxy URL
            "/cms9pQ8*windowId!s/https://example.com/a/b?x=1",                 // TestCafe resource URL
            "/a//b",                                                          // empty segment
            "/a/b%2Fc",                                                       // encoded separator
            "/a/%2e%2e/b",                                                    // encoded dot segment
    })
    void ambiguousPaths_areForwardedVerbatim(String pathQuery) throws Exception {
        String response = proxyGet(pathQuery);

        assertThat(response.lines().findFirst().orElse(""))
                .as("status for %s", pathQuery).contains("200 OK");
        assertThat(response).as("forwarded target for %s", pathQuery)
                .contains("SAW=GET " + pathQuery + " HTTP/1.1");
    }
}
