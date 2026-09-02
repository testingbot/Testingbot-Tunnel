package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --log-http} observed through the log output it actually produces.
 *
 * <p>Asserting on captured records rather than on the handler's internals is the point: the
 * value of this feature is what a customer sees when they go looking for a failed request, and
 * the redaction in particular is only worth anything if it survives the real path.
 */
class HttpLoggingTest {

    private ServerSocket origin;
    private ExecutorService pool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private int originPort;
    private int proxyPort;
    private CapturingHandler captured;
    private Logger logHandlerLogger;

    /** Collects what HttpLogHandler emits. */
    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        synchronized String all() {
            return String.join("\n", messages);
        }

        synchronized int count() {
            return messages.size();
        }
    }

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    private void startOrigin(int statusToReturn) throws Exception {
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
                            in.readLine();
                            List<String> received = new ArrayList<>();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                received.add(line);
                            }
                            // Echoed so a test can assert on what the origin was actually sent.
                            byte[] body = String.join("\n", received).getBytes(StandardCharsets.UTF_8);
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 " + statusToReturn + " Status\r\nContent-Length: "
                                    + body.length + "\r\nConnection: close\r\n\r\n")
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
    }

    private void startProxy(String mode, String requestIdHeader) throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setLogHttp(mode);
        if (requestIdHeader != null) {
            app.setRequestIdHeader(requestIdHeader);
        }

        captured = new CapturingHandler();
        logHandlerLogger = Logger.getLogger("com.testingbot.tunnel.proxy.HttpLogHandler");
        logHandlerLogger.addHandler(captured);
        logHandlerLogger.setLevel(Level.ALL);

        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (logHandlerLogger != null && captured != null) {
            logHandlerLogger.removeHandler(captured);
        }
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

    /** @return the response body, which is the origin's echo of the headers it received */
    private String proxyGetEchoing(String extraHeaders) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET http://127.0.0.1:" + originPort
                    + "/page HTTP/1.1\r\nHost: 127.0.0.1:" + originPort + "\r\n"
                    + extraHeaders + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
            }
            Thread.sleep(200);
            return all.toString();
        }
    }

    private void proxyGet(String extraHeaders) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://127.0.0.1:" + originPort + "/page HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + extraHeaders
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (reader.readLine() != null) {
                // drain so the exchange completes before we look at the log
            }
        }
        Thread.sleep(200);
    }

    @Test
    void none_logsNothing() throws Exception {
        startOrigin(200);
        startProxy("none", null);

        proxyGet("");

        assertThat(captured.count()).isZero();
    }

    @Test
    void url_logsOneLinePerRequestWithoutHeaders() throws Exception {
        startOrigin(200);
        startProxy("url", null);

        proxyGet("X-Custom: visible\r\n");

        String logged = captured.all();
        assertThat(logged).contains("GET");
        assertThat(logged).contains("/page");
        assertThat(logged).contains("200");
        assertThat(logged).doesNotContain("X-Custom");
    }

    @Test
    void headers_includesRequestHeaders() throws Exception {
        startOrigin(200);
        startProxy("headers", null);

        proxyGet("X-Custom: visible\r\n");

        assertThat(captured.all()).contains("X-Custom: visible");
    }

    @Test
    void headers_redactsCredentials() throws Exception {
        // Without this these logs would carry Authorization into whatever collects them.
        startOrigin(200);
        startProxy("headers", null);

        proxyGet("Authorization: Bearer super-secret-token\r\n");

        String logged = captured.all();
        assertThat(logged).contains("Authorization");
        assertThat(logged).doesNotContain("super-secret-token");
    }

    @Test
    void errors_staysQuietForSuccessfulRequests() throws Exception {
        startOrigin(200);
        startProxy("errors", null);

        proxyGet("");

        assertThat(captured.count()).isZero();
    }

    @Test
    void errors_logsWithHeadersOnServerError() throws Exception {
        startOrigin(500);
        startProxy("errors", null);

        proxyGet("X-Custom: visible\r\n");

        String logged = captured.all();
        assertThat(logged).contains("500");
        assertThat(logged).contains("X-Custom: visible");
    }

    @Test
    void reusesAnIncomingCorrelationId() throws Exception {
        // A trace that starts in the test framework should stay joined up through the tunnel.
        startOrigin(200);
        startProxy("url", null);

        proxyGet("X-Request-Id: caller-supplied-id\r\n");

        assertThat(captured.all()).contains("[caller-supplied-id]");
    }

    @Test
    void generatesACorrelationIdWhenTheCallerSuppliesNone() throws Exception {
        startOrigin(200);
        startProxy("url", null);

        proxyGet("");

        assertThat(captured.all()).matches("(?s).*\\[[0-9a-f]+\\].*");
    }

    @Test
    void passesTheCorrelationIdToTheOrigin() throws Exception {
        // So the origin's own logs can be lined up with the tunnel's.
        startOrigin(200);
        startProxy("url", null);

        String seenByOrigin = proxyGetEchoing("X-Request-Id: caller-supplied-id\r\n");

        assertThat(seenByOrigin).contains("caller-supplied-id");
        assertThat(captured.all()).contains("[caller-supplied-id]");
    }

    @Test
    void passesAGeneratedIdToTheOrigin_evenWhenLoggingIsOff() throws Exception {
        // Correlation is useful to the origin regardless of how much we log locally.
        startOrigin(200);
        startProxy("none", null);

        String seenByOrigin = proxyGetEchoing("");

        assertThat(seenByOrigin).containsIgnoringCase("X-Request-Id");
    }

    @Test
    void honoursACustomCorrelationHeader() throws Exception {
        startOrigin(200);
        startProxy("url", "X-Trace");

        proxyGet("X-Trace: my-trace-id\r\n");

        assertThat(captured.all()).contains("[my-trace-id]");
    }
}
