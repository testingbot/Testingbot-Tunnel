package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported byte total must never go backwards across an SSH reconnect.
 *
 * <p>{@link com.testingbot.tunnel.ssh.SSHTunnel}'s connection monitor stops and restarts the
 * <em>same</em> {@link HttpProxy} instance. Jetty resets its {@code ConnectionStatistics} in
 * {@code doStart()}, so the figure the status endpoint reads is only meaningful if the proxy
 * banks the old connector's total and re-arms against the new one -- exercised here through
 * the real lifecycle rather than by re-installing the supplier by hand, which is a step
 * production never performs.
 */
class BytesTransferredLifecycleTest {

    private HttpProxy httpProxy;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Statistics.reset();
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
    }

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        Statistics.reset();
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

    /** Drives a request through the proxy and waits for the connection to close. */
    private void moveSomeBytes() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            // Target is closed, so this returns an error -- irrelevant here; what matters is
            // that bytes crossed the connector and the connection then closed.
            String request = "GET http://127.0.0.1:1/nothing HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:1\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (in.readLine() != null) {
                // drain until the server closes, so the bytes are recorded
            }
        }
    }

    private long settledTotal() throws Exception {
        long previous = -1;
        for (int i = 0; i < 100; i++) {
            long now = Statistics.getBytesTransferred();
            if (now > 0 && now == previous) {
                return now;
            }
            previous = now;
            Thread.sleep(50);
        }
        return Statistics.getBytesTransferred();
    }

    @Test
    void total_doesNotResetOrFreezeWhenTheProxyRestarts() throws Exception {
        moveSomeBytes();
        long afterFirst = settledTotal();
        assertThat(afterFirst).as("bytes recorded before the reconnect").isPositive();

        // What CustomConnectionMonitor does on an SSH reconnect.
        httpProxy.stop();
        httpProxy.start();
        waitForPort(proxyPort);

        assertThat(Statistics.getBytesTransferred())
                .as("total must not drop when the connector is replaced")
                .isGreaterThanOrEqualTo(afterFirst);

        moveSomeBytes();
        long afterSecond = settledTotal();
        assertThat(afterSecond)
                .as("total must keep growing after the reconnect, not freeze")
                .isGreaterThan(afterFirst);
    }
}
