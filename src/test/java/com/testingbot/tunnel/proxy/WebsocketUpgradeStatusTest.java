package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the WebSocket relay accepts as a successful upgrade, driven through the real handler.
 *
 * <p>{@link HttpStatusLineTest} covers the parser; this covers the wiring, because the bug was
 * never in a helper -- {@code WebsocketHandler} asked whether the target's first line
 * {@code contains("101")} and, when it did, answered the client {@code 101 Switching Protocols}
 * and spliced the two sockets together. A client then held what it believed was a WebSocket to a
 * server that had refused it.
 *
 * <p>{@code ConnectFramingTest.aStatusLineMentioning200InItsReasonIsStillARejection} is the same
 * test on the CONNECT path, which has had it all along. This is the one the WebSocket path never
 * got.
 */
class WebsocketUpgradeStatusTest {

    private ServerSocket target;
    private ExecutorService pool;
    private HttpProxy httpProxy;
    private int proxyPort;

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (target != null && !target.isClosed()) {
            target.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            // A stale interrupt left by another component's inline shutdown would otherwise
            // fail this teardown for reasons unrelated to the test; the same guard
            // ConnectFramingTest carries.
            Thread.interrupted();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** A stand-in target that answers every upgrade with {@code statusLine}. */
    private void startTarget(String statusLine) throws IOException {
        target = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (!target.isClosed()) {
                try {
                    Socket socket = target.accept();
                    pool.submit(() -> {
                        try {
                            BufferedReader in = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8));
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                // drain the upgrade request
                            }
                            OutputStream out = socket.getOutputStream();
                            out.write((statusLine + "\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            Thread.sleep(30_000);
                        } catch (Exception ignored) {
                            // test finished
                        }
                    });
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    private void startTunnel() throws Exception {
        proxyPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    /** Sends a ws:// upgrade through the tunnel and returns the client's first response line. */
    private String upgradeThroughTunnel() throws Exception {
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(15_000);
            String host = "127.0.0.1:" + target.getLocalPort();
            String request = "GET http://" + host + "/ws HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            return first == null ? "" : first;
        }
    }

    @Test
    void aGenuineUpgradeIsRelayed() throws Exception {
        startTarget("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket");
        startTunnel();

        assertThat(upgradeThroughTunnel())
                .as("a real 101 must still be relayed; the fix must not break the feature")
                .contains("101");
    }

    @Test
    void aFailureWhoseReasonPhraseMentions101IsNotAnUpgrade() throws Exception {
        // The realistic shape of the bug: the target refuses, and its own error text carries the
        // digits. contains("101") read that as success and handed the client a 101.
        startTarget("HTTP/1.1 500 Internal Error 101 in upstream handler");
        startTunnel();

        assertThat(upgradeThroughTunnel())
                .as("a 500 is a refusal however its reason phrase reads")
                .doesNotContain("101 Switching Protocols");
    }

    @Test
    void aStatusCodeMerelyContaining101IsNotAnUpgrade() throws Exception {
        startTarget("HTTP/1.1 2101 Nonsense");
        startTunnel();

        assertThat(upgradeThroughTunnel()).doesNotContain("101 Switching Protocols");
    }

    @Test
    void aPlainOkIsNotAnUpgrade() throws Exception {
        // A server that ignored the Upgrade header and answered the GET normally. Relaying that
        // as a WebSocket produces a connection that simply hangs.
        startTarget("HTTP/1.1 200 OK");
        startTunnel();

        assertThat(upgradeThroughTunnel()).doesNotContain("101 Switching Protocols");
    }
}
