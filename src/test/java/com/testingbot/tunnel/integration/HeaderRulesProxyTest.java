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
 * {@code --header} and {@code --response-header} against a real origin, in both directions.
 *
 * <p>The response side is the part worth proving end to end: Jetty's ProxyHandler offers only a
 * per-field filter and no after-the-copy hook, so overriding a header the origin also sends
 * depends on seeding the value before the exchange and dropping the origin's copy as it passes.
 */
class HeaderRulesProxyTest {

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
                            in.readLine();
                            List<String> received = new ArrayList<>();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                received.add(line);
                            }
                            byte[] body = String.join("\n", received).getBytes(StandardCharsets.UTF_8);
                            OutputStream out = socket.getOutputStream();
                            // The origin sets headers the rules will have to override or drop.
                            out.write(("HTTP/1.1 200 OK\r\n"
                                    + "Content-Security-Policy: default-src 'none'\r\n"
                                    + "X-Origin-Only: present\r\n"
                                    + "X-Debug-A: 1\r\n"
                                    + "X-Debug-B: 2\r\n"
                                    + "Content-Length: " + body.length + "\r\n"
                                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
        app.setHeaderRules(new String[]{
                "X-Added: from-tunnel",
                "-X-Strip-Me",
                "-X-Ambient-*",
                "X-Blank;"});
        app.setResponseHeaderRules(new String[]{
                "-Content-Security-Policy",
                "X-Origin-Only: overridden",
                "X-Injected: added-by-tunnel",
                "-X-Debug-*"});
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
            // Clear a stale interrupt before waiting. MINA's and JSch's shutdown run
            // inline on this thread and can leave the flag set, which makes
            // awaitTermination throw immediately and fail teardown for a test that had
            // nothing to do with it -- seen in CI on
            // aLocalPortAlreadyInUseIsReportedAndLeavesForwardingIncomplete. A leftover
            // flag here is finished-test state, not a cancellation of the suite.
            Thread.interrupted();
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

    /** @return the whole proxy response, headers and body (the body echoes what the origin saw) */
    private String proxyGet() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            String request = "GET http://127.0.0.1:" + originPort + "/ HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + "X-Strip-Me: should-not-arrive\r\n"
                    + "X-Ambient-Trace: should-not-arrive\r\n"
                    + "X-Blank: not-empty\r\n"
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
    void requestRules_reachTheOrigin() throws Exception {
        String response = proxyGet();

        assertThat(response).contains("200 OK");
        // The body is the origin's echo of the headers it received.
        assertThat(response).contains("X-Added: from-tunnel");
        assertThat(response).doesNotContain("should-not-arrive");
        assertThat(response).contains("X-Blank: ");
    }

    @Test
    void responseRules_editWhatTheBrowserSees() throws Exception {
        String response = proxyGet();
        String headers = response.substring(0, response.indexOf("\n\n") + 1);

        assertThat(headers).doesNotContain("Content-Security-Policy");
        assertThat(headers).contains("X-Origin-Only: overridden");
        assertThat(headers).doesNotContain("X-Origin-Only: present");
        assertThat(headers).contains("X-Injected: added-by-tunnel");
        assertThat(headers).doesNotContain("X-Debug-A");
        assertThat(headers).doesNotContain("X-Debug-B");
    }
}
