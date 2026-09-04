package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --log-http none} means no per-request logging, CONNECT included.
 *
 * <p>CustomConnectHandler logged an INFO line for every CONNECT regardless of the setting, so a
 * user who had explicitly asked for silence still got a line per tunnelled connection -- and the
 * line cost a global logger lookup on the request path to produce.
 *
 * <p>This test was itself wrong for a while: it watched CustomConnectHandler, which writes no
 * per-request line, and matched a "[CONNECT]" prefix that appears nowhere in the source, so it
 * passed whatever the code did. It now watches HttpLogHandler, which is what emits the line, and
 * reads the formatted message rather than the unsubstituted pattern.
 *
 * <p>{@code --debug} is separate and does come from this handler: it dumps the CONNECT request
 * headers, redacted.
 */
class ConnectLoggingTest {

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        /**
         * Formatted, not the raw pattern: getMessage() leaves "{0}" unsubstituted, so an
         * assertion about what was logged would never match whatever the code did.
         */
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

    private HttpProxy httpProxy;
    private int proxyPort;
    private Logger connectLogger;
    private CapturingHandler captured;

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    private Logger debugLogger;
    private CapturingHandler debugCaptured;

    @BeforeEach
    void setUp() throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setLogHttp("none");

        captured = new CapturingHandler();
        // HttpLogHandler is what writes the per-request line, for CONNECT as for anything
        // else. This listened to CustomConnectHandler, which never logs one.
        connectLogger = Logger.getLogger("com.testingbot.tunnel.proxy.HttpLogHandler");
        connectLogger.addHandler(captured);
        connectLogger.setLevel(Level.ALL);

        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() {
        if (connectLogger != null && captured != null) {
            connectLogger.removeHandler(captured);
        }
        if (debugLogger != null && debugCaptured != null) {
            debugLogger.removeHandler(debugCaptured);
        }
        if (httpProxy != null) {
            httpProxy.stop();
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

    @Test
    void aConnectProducesNoPerRequestLineWhenLoggingIsOff() throws Exception {
        // The destination does not need to exist: the line was emitted on the way in, before
        // any dial was attempted.
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(
                    ("CONNECT nowhere.invalid:443 HTTP/1.1\r\nHost: nowhere.invalid:443\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.getInputStream().read();
        } catch (IOException expected) {
            // the tunnel cannot be established; only the logging matters here
        }
        Thread.sleep(300);

        assertThat(captured.messages())
                .as("no per-CONNECT line should be emitted under --log-http none")
                .noneMatch(m -> m.contains("CONNECT"));
    }

    /**
     * The positive control for the test above.
     *
     * <p>Without it, that assertion held for a HttpLogHandler that had stopped wrapping the
     * CONNECT path altogether, or whose logger had been renamed -- nothing else in the suite
     * drives a CONNECT through the logging handler, since HttpLoggingTest only sends plain GETs.
     */
    @Test
    void aConnectIsLoggedAtUrlLevel() throws Exception {
        int port = findFreePort();
        App app = new App();
        app.setJettyPort(port);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setLogHttp("url");

        CapturingHandler urlCaptured = new CapturingHandler();
        Logger logger = Logger.getLogger("com.testingbot.tunnel.proxy.HttpLogHandler");
        logger.addHandler(urlCaptured);
        logger.setLevel(Level.ALL);

        HttpProxy urlProxy = new HttpProxy(app);
        try {
            waitForPort(port);
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                socket.getOutputStream().write(
                        ("CONNECT nowhere.invalid:443 HTTP/1.1\r\nHost: nowhere.invalid:443\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                socket.getInputStream().read();
            } catch (IOException expected) {
                // the dial cannot succeed; the line is written on the way in regardless
            }
            Thread.sleep(300);

            assertThat(urlCaptured.messages())
                    .as("--log-http url must log the CONNECT")
                    .anyMatch(m -> m.contains("CONNECT"));
        } finally {
            logger.removeHandler(urlCaptured);
            urlProxy.stop();
        }
    }

    @Test
    void debugDumpsTheConnectHeaders() throws Exception {
        // HttpProxy set six properties on the cast CONNECT handler and not this one, so --debug
        // dumped nothing for CONNECT -- and never logged the line saying an upstream-proxy
        // handshake had completed, on the path where that is the thing being debugged.
        if (httpProxy != null) {
            httpProxy.stop();
        }
        int debugPort = findFreePort();
        App app = new App();
        app.setJettyPort(debugPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setDebugMode(true);

        debugCaptured = new CapturingHandler();
        debugLogger = Logger.getLogger("com.testingbot.tunnel.proxy.CustomConnectHandler");
        debugLogger.addHandler(debugCaptured);
        debugLogger.setLevel(Level.ALL);

        httpProxy = new HttpProxy(app);
        waitForPort(debugPort);

        try (Socket socket = new Socket("127.0.0.1", debugPort)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(("CONNECT nowhere.invalid:443 HTTP/1.1\r\n"
                    + "Host: nowhere.invalid:443\r\nX-Marker: seen\r\n"
                    + "Proxy-Authorization: Basic c2VjcmV0\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.getInputStream().read();
        } catch (IOException expected) {
            // the destination does not resolve; only the logging matters here
        }
        Thread.sleep(300);

        String log = String.join("\n", debugCaptured.messages());
        assertThat(log).contains("X-Marker: seen");
        // Redacted on the way out, as everywhere else that prints a header value.
        assertThat(log).doesNotContain("c2VjcmV0");
    }
}
