package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpForwarder;
import org.junit.jupiter.api.AfterEach;
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
 * The Selenium relay honouring {@code --log-http}.
 *
 * <p>It did not. Every relayed request produced an INFO line whatever the user asked for,
 * including {@code --log-http none}. The proxy chain was fixed for this in TB-319 and this path
 * was missed; per-module logging is what made it visible, because
 * {@code --log-http forwarder:none} had nothing to switch off.
 */
class ForwarderLoggingTest {

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        /**
         * The formatted message, not the raw pattern.
         *
         * <p>getMessage() returns "[{0}] {1}" with the parameters unsubstituted, so asserting on
         * a URL would never match whatever the code did -- the first version of this test passed
         * against a deliberately reintroduced bug for exactly that reason.
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

    private HttpForwarder forwarder;
    private Logger forwarderLogger;
    private CapturingHandler captured;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        if (forwarderLogger != null && captured != null) {
            forwarderLogger.removeHandler(captured);
        }
        if (forwarder != null) {
            forwarder.stop();
        }
    }

    /** Starts the relay with the given --log-http value and sends one request through it. */
    private List<String> relayOneRequest(String logHttp) throws Exception {
        int port = findFreePort();
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setSeleniumPort(port);
        if (logHttp != null) {
            app.setLogHttp(logHttp);
        }

        captured = new CapturingHandler();
        forwarderLogger = Logger.getLogger("com.testingbot.tunnel.proxy.ForwarderHandler");
        forwarderLogger.addHandler(captured);
        forwarderLogger.setLevel(Level.ALL);

        // The constructor starts it.
        forwarder = new HttpForwarder(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                break;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }

        // The hub is unreachable from a test, which does not matter: the line under test is
        // written on the way in, before anything is forwarded.
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(
                    ("GET /wd/hub/status HTTP/1.1\r\nHost: 127.0.0.1:" + port
                     + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.getInputStream().read();
        } catch (IOException expected) {
            // only the logging matters here
        }
        Thread.sleep(300);
        return captured.messages();
    }

    @Test
    void noneSilencesTheRelay() throws Exception {
        assertThat(relayOneRequest("none"))
                .as("--log-http none must silence this path too")
                .noneMatch(m -> m.contains("/wd/hub"));
    }

    @Test
    void theRelayCanBeSilencedOnItsOwn() throws Exception {
        // The case per-module logging exists for: quiet relay, browser traffic untouched.
        assertThat(relayOneRequest("forwarder:none,proxy:url"))
                .noneMatch(m -> m.contains("/wd/hub"));
    }

    @Test
    void urlTurnsTheRelayBackOn() throws Exception {
        assertThat(relayOneRequest("forwarder:url"))
                .as("asking for it should produce the line the relay always used to log")
                .anyMatch(m -> m.contains("/wd/hub"));
    }

    @Test
    void theDefaultIsQuietForSuccessfulRequests() throws Exception {
        // errors is the documented default since TB-319. This path ignored it entirely.
        assertThat(relayOneRequest(null))
                .noneMatch(m -> m.contains("/wd/hub"));
    }
}
