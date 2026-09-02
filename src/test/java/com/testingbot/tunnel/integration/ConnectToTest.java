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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --connect-to} must change only where we dial.
 *
 * <p>The target here is {@code prod.example.invalid}, a name that deliberately does not resolve.
 * If the remap happened anywhere other than the dial path -- by rewriting the request URI, say --
 * the origin would see the substituted host in its {@code Host} header, and a test driving a
 * production URL against a staging box would exercise the wrong virtual host.
 */
class ConnectToTest {

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
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            String requestLine = in.readLine();
                            List<String> headers = new ArrayList<>();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                headers.add(line);
                            }
                            byte[] body = (requestLine + "\n" + String.join("\n", headers))
                                    .getBytes(StandardCharsets.UTF_8);
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
        app.setConnectTo(new String[]{"prod.example.invalid:80:127.0.0.1:" + originPort});
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

    private String proxyGet(String url, String hostHeader) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET " + url + " HTTP/1.1\r\nHost: " + hostHeader
                    + "\r\nConnection: close\r\n\r\n";
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
    void dialsTheSubstituteButKeepsTheOriginalHost() throws Exception {
        String response = proxyGet("http://prod.example.invalid/page", "prod.example.invalid");

        // Reaching the origin at all proves the dial was redirected: the name does not resolve,
        // so without --connect-to this could only have failed.
        assertThat(response).contains("200 OK");
        assertThat(response).contains("GET /page HTTP/1.1");
        assertThat(response).contains("Host: prod.example.invalid");
        assertThat(response).doesNotContain("Host: 127.0.0.1");
    }

    @Test
    void unmatchedHosts_areLeftAlone() throws Exception {
        // No rule for this name, and it does not resolve, so it must fail rather than
        // silently fall through to the substitute origin.
        String response = proxyGet("http://other.example.invalid/page", "other.example.invalid");

        assertThat(response).doesNotContain("200 OK");
    }
}
