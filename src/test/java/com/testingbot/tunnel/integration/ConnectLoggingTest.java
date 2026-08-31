package com.testingbot.tunnel.integration;

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
 */
class ConnectLoggingTest {

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            messages.add(String.valueOf(record.getMessage()));
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
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setLogHttp("none");

        captured = new CapturingHandler();
        connectLogger = Logger.getLogger("com.testingbot.tunnel.proxy.CustomConnectHandler");
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
                .noneMatch(m -> m.startsWith("[CONNECT]"));
    }
}
