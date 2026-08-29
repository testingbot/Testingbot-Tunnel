package com.testingbot.tunnel.integration;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jetty 11's proxy servlet added the X-Forwarded-* family; Jetty 12's ProxyHandler sends only
 * Via and Forwarded. Staging environments behind a load balancer routinely key off
 * X-Forwarded-Proto and X-Forwarded-Host, so losing them in the migration changed how customer
 * applications behaved when reached through the tunnel.
 */
class ForwardedHeadersTest {

    private ServerSocket origin;
    private ExecutorService pool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int originPort;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        origin = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        originPort = origin.getLocalPort();
        pool = Executors.newCachedThreadPool();

        // Echoes every header it received, so the test sees exactly what the proxy forwarded.
        acceptor = new Thread(() -> {
            while (!origin.isClosed()) {
                try {
                    Socket accepted = origin.accept();
                    pool.submit(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            in.readLine();
                            List<String> headers = new ArrayList<>();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                headers.add(line);
                            }
                            byte[] body = String.join("\n", headers).getBytes(StandardCharsets.UTF_8);
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
        app.addCustomHeader("X-Tb-Extra", "added");
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

    private String proxyGet(String extraRequestHeaders) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://127.0.0.1:" + originPort + "/ HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + extraRequestHeaders
                    + "Connection: close\r\n\r\n";
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

    @Test
    void forwardedHeaders_areSentToTheOrigin() throws Exception {
        String seen = proxyGet("");

        assertThat(seen).contains("X-Forwarded-For: 127.0.0.1");
        assertThat(seen).contains("X-Forwarded-Proto: http");
        assertThat(seen).contains("X-Forwarded-Host: 127.0.0.1:" + originPort);
        assertThat(seen).contains("X-Forwarded-Server: ");
    }

    @Test
    void forwardedServer_namesTheProxyNotTheDestination() throws Exception {
        String seen = proxyGet("");

        // getServerName() would return the target host, making this a copy of
        // X-Forwarded-Host rather than identifying the hop that forwarded the request.
        assertThat(seen).doesNotContain("X-Forwarded-Server: 127.0.0.1:" + originPort);
    }

    @Test
    void extraHeaders_areAddedAlongsideAClientValueRatherThanReplacingIt() throws Exception {
        String seen = proxyGet("X-Tb-Extra: from-client\r\n");

        assertThat(seen).contains("from-client");
        assertThat(seen).contains("added");
    }
}
