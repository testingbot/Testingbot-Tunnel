package com.testingbot.tunnel.integration;

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
 * RFC 9112 3.2.2 requires absolute-form on requests to a forward proxy.
 *
 * <p>jetty-client normally rewrites the target itself, but only when {@code getURI()} is
 * non-null, and {@code path()} leaves it null for precisely the lenient query strings the
 * proxy goes out of its way to forward. Those requests reached the upstream proxy in
 * origin-form, which Squid answers with 400 -- so the combination of {@code --proxy} and a
 * browser-normal-but-not-RFC-3986 query string failed while each worked alone.
 */
class UpstreamProxyAbsoluteFormTest {

    private ServerSocket upstream;
    private ExecutorService pool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Stands in for Squid: echoes back the request line it was given.
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        acceptor = new Thread(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket accepted = upstream.accept();
                    pool.submit(() -> {
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
        app.setProxy("127.0.0.1:" + upstream.getLocalPort());
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
        if (upstream != null && !upstream.isClosed()) {
            upstream.close();
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

    private String proxyGet(String pathQuery) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://example.test" + pathQuery + " HTTP/1.1\r\n"
                    + "Host: example.test\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
            }
            return all.toString();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/ok?a=1",
            "/?a=1&b={json}",
            "/?redirect=http://x|y",
            "/?path=a[0]",
    })
    void requestsToTheUpstreamProxy_useAbsoluteForm(String pathQuery) throws Exception {
        String response = proxyGet(pathQuery);

        assertThat(response).as("upstream saw a request at all for %s", pathQuery).contains("SAW=GET ");
        assertThat(response).as("absolute-form target for %s", pathQuery)
                .contains("SAW=GET http://example.test" + pathQuery + " HTTP/1.1");
    }
}
