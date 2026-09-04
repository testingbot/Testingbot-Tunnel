package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.sun.net.httpserver.HttpServer;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpForwarder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --log-http forwarder:body} end to end.
 *
 * <p>Two things have to hold at once and they pull against each other: the log must not contain
 * the credentials that WebDriver capabilities routinely carry, and the hub must receive the body
 * byte for byte. A tee that redacted the log by consuming the buffer would satisfy the first and
 * silently break every Selenium session, so both are asserted on the same request.
 */
class ForwarderBodyLoggingTest {

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();
        private final java.util.logging.Formatter formatter =
                new java.util.logging.SimpleFormatter();

        @Override
        public synchronized void publish(LogRecord record) {
            messages.add(formatter.formatMessage(record));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        synchronized List<String> messages() {
            return new ArrayList<>(messages);
        }
    }

    private HttpForwarder forwarder;
    private HttpServer hub;
    private Logger forwarderLogger;
    private CapturingHandler captured;
    private final List<String> received = new CopyOnWriteArrayList<>();

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @AfterEach
    void tearDown() {
        if (forwarderLogger != null && captured != null) {
            forwarderLogger.removeHandler(captured);
        }
        if (forwarder != null) {
            forwarder.stop();
        }
        if (hub != null) {
            hub.stop(0);
        }
    }

    /** Stands in for the hub, recording exactly what reached it. */
    private int startHub() throws IOException {
        int port = findFreePort();
        hub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        hub.createContext("/", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = "{\"value\":{\"sessionId\":\"abc\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        hub.start();
        return port;
    }

    private int startRelay(String logHttp) throws Exception {
        int hubPort = startHub();
        int relayPort = findFreePort();

        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setSeleniumPort(relayPort);
        app.setLogHttp(logHttp);
        // The relay forwards to 127.0.0.1:<sshPort>; there is no setter, so point it at the
        // stand-in hub directly.
        java.lang.reflect.Field sshPort = App.class.getDeclaredField("sshPort");
        sshPort.setAccessible(true);
        sshPort.set(app, hubPort);

        captured = new CapturingHandler();
        forwarderLogger = Logger.getLogger("com.testingbot.tunnel.proxy.ForwarderHandler");
        forwarderLogger.addHandler(captured);
        forwarderLogger.setLevel(Level.ALL);

        forwarder = new HttpForwarder(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", relayPort)) {
                break;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        return relayPort;
    }

    private String post(int relayPort, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", relayPort)) {
            socket.setSoTimeout(20_000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("POST /wd/hub/session HTTP/1.1\r\nHost: 127.0.0.1:"
                    + relayPort + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buffer)) > 0) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
            return response.toString();
        }
    }

    /**
     * Waits for the relay to have logged {@code fragment}, up to two seconds.
     *
     * <p>The log record is written after the response has been relayed, so a test that reads the
     * response and asserts immediately is racing it. This used to be a fixed 200ms sleep in
     * post(), which held on a developer machine and failed on two of the three JDKs in CI --
     * the body was logged there too, just after the assertion had already run.
     *
     * @return every captured message, joined, so the caller can assert on the content
     */
    private String awaitLog(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        String log = "";
        while (System.nanoTime() < deadline) {
            log = String.join("\n", captured.messages());
            if (log.contains(fragment)) {
                return log;
            }
            Thread.sleep(20);
        }
        return log;
    }

    private static final String CAPABILITIES =
            "{\"capabilities\":{\"alwaysMatch\":{\"browserName\":\"chrome\","
            + "\"tb:options\":{\"key\":\"REAL-KEY-abc\",\"secret\":\"REAL-SECRET-xyz\","
            + "\"name\":\"my test\"}}}}";

    @Test
    void theBodyIsLoggedRedactedAndForwardedIntact() throws Exception {
        int relayPort = startRelay("forwarder:body");

        assertThat(post(relayPort, CAPABILITIES)).contains("sessionId");

        // The hub must have received exactly what the client sent. A tee that consumed the
        // buffer to read it would fail here while the log still looked perfect.
        assertThat(received)
                .as("the body must reach the hub byte for byte")
                .containsExactly(CAPABILITIES);

        String log = awaitLog("body:");
        assertThat(log).contains("body:");
        assertThat(log)
                .as("credentials from the capabilities must not reach the log")
                .doesNotContain("REAL-KEY-abc")
                .doesNotContain("REAL-SECRET-xyz");
        assertThat(log).contains("<redacted>");
        // Still useful: the parts worth debugging survive.
        assertThat(log).contains("chrome").contains("my test");
    }

    @Test
    void theBodyLevelAlsoLogsTheHeaders() throws Exception {
        // BODY is documented as "as HEADERS, plus the request body". The guard compared against
        // HEADERS by name, so the most verbose level printed strictly less than the one below
        // it: the body appeared and the headers it belonged to did not.
        int relayPort = startRelay("forwarder:body");

        post(relayPort, CAPABILITIES);

        String log = awaitLog("body:");
        assertThat(log).contains("Content-Type: application/json");
        assertThat(log).contains("body:");
    }

    @Test
    void nothingIsLoggedWithoutTheBodyLevel() throws Exception {
        int relayPort = startRelay("forwarder:url");

        post(relayPort, CAPABILITIES);

        assertThat(received).containsExactly(CAPABILITIES);
        // Wait for the record this level *does* emit before asserting what is missing from it.
        // Asserting on an empty capture would pass for the wrong reason -- and keep passing if
        // body logging were switched on for every level.
        String log = awaitLog("/wd/hub/session");
        assertThat(log)
                .as("a body should never appear unless it was asked for")
                .doesNotContain("body:")
                .doesNotContain("REAL-KEY-abc");
    }
}
