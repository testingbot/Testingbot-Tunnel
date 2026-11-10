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
 * The proxy must forward the client's User-Agent as the only one.
 *
 * <p>jetty-client adds its own {@code User-Agent: Jetty/<version>} to every request unless the
 * client's user agent field is cleared, and ProxyHandler then also copied the browser's header.
 * The origin saw two, the first being Jetty's. Servers that read the first value treated every
 * browser as "Jetty". TestCafe, for one, reported each remote browser as "Other 0.0 / Other 0.0".
 */
class UserAgentForwardingTest {

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
                            StringBuilder headers = new StringBuilder();
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                headers.append("HEADER=").append(line).append('\n');
                            }
                            byte[] body = headers.toString().getBytes(StandardCharsets.UTF_8);
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
    private String proxyGet(String extraHeaders) throws Exception {
        String pathQuery = "/";
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://127.0.0.1:" + originPort + pathQuery + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + extraHeaders
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

    private static long userAgentHeaders(String response) {
        return response.lines().filter(l -> l.regionMatches(true, 0, "HEADER=User-Agent:", 0, 18)).count();
    }

    @Test
    void browserUserAgent_isTheOnlyOneForwarded() throws Exception {
        String browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36";

        String response = proxyGet("User-Agent: " + browser + "\r\n");

        assertThat(response.lines().findFirst().orElse("")).contains("200 OK");
        assertThat(response).contains("HEADER=User-Agent: " + browser);
        assertThat(userAgentHeaders(response)).as("User-Agent headers in:\n%s", response).isEqualTo(1);
    }

    @Test
    void noUserAgent_isNotInvented() throws Exception {
        String response = proxyGet("");

        assertThat(response.lines().findFirst().orElse("")).contains("200 OK");
        assertThat(userAgentHeaders(response)).as("User-Agent headers in:\n%s", response).isZero();
    }
}
