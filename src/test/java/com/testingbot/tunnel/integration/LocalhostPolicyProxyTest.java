package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpForwarder;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --localhost-policy deny} applied to real traffic.
 *
 * <p>The case worth pinning is the one that would make this feature unshippable: the Selenium
 * relay targets 127.0.0.1 by design, so a policy that caught it would break every test session
 * the moment someone enabled it.
 */
class LocalhostPolicyProxyTest {

    private ServerSocket origin;
    private ExecutorService pool;
    private Thread acceptor;
    private HttpProxy httpProxy;
    private HttpForwarder forwarder;
    private int originPort;
    private int proxyPort;

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    private void startOrigin() throws Exception {
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
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                // drain
                            }
                            byte[] body = "LOCAL".getBytes(StandardCharsets.UTF_8);
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
    }

    private App startProxy(String policy) throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        if (policy != null) {
            app.setLocalhostPolicy(policy);
        }
        httpProxy = new HttpProxy(app);
        waitForPort(proxyPort);
        return app;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (forwarder != null) {
            forwarder.stop();
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
        throw new IllegalStateException("Nothing started on port " + port);
    }

    private String proxyGet(String url, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + url + " HTTP/1.1\r\nHost: " + host
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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
    void allowIsTheDefault_soLocalServicesStayReachable() throws Exception {
        startOrigin();
        startProxy(null);

        String response = proxyGet("http://127.0.0.1:" + originPort + "/", "127.0.0.1:" + originPort);

        assertThat(response).contains("200 OK");
        assertThat(response).contains("LOCAL");
    }

    @Test
    void deny_refusesLoopbackWithAClassifiedError() throws Exception {
        startOrigin();
        startProxy("deny");

        String response = proxyGet("http://127.0.0.1:" + originPort + "/", "127.0.0.1:" + originPort);

        assertThat(response).contains("403");
        assertThat(response).contains("denied-localhost");
        assertThat(response).doesNotContain("LOCAL");
    }

    @Test
    void deny_doesNotBreakTheSeleniumRelay() throws Exception {
        // The relay is a separate server with its own handler, so the proxy chain's policy must
        // not reach it -- it targets 127.0.0.1 by design.
        App app = startProxy("deny");
        int seleniumPort = findFreePort();
        app.setSeleniumPort(seleniumPort);

        // A stub hub behind the relay, so a *successful* relay is observable. Without one the
        // answer is always 502, and "not 403" held for a relay broken in any way at all --
        // nothing in HttpForwarder -> ForwarderHandler can even produce a 403.
        com.sun.net.httpserver.HttpServer hub = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        hub.createContext("/", exchange -> {
            byte[] body = "{\"value\":{\"ready\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        hub.start();
        java.lang.reflect.Field sshPort = App.class.getDeclaredField("sshPort");
        sshPort.setAccessible(true);
        sshPort.set(app, hub.getAddress().getPort());

        forwarder = new HttpForwarder(app);
        waitForPort(seleniumPort);

        // The relay answers on its own port, unaffected by the proxy chain's policy.
        try (Socket socket = new Socket("127.0.0.1", seleniumPort)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(
                    ("GET /wd/hub/status HTTP/1.1\r\nHost: 127.0.0.1:" + seleniumPort
                     + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            hub.stop(0);

            // A real 200: the relay reached the hub with --localhost-policy deny in force,
            // which is the point. The previous "not 403" was satisfied by the 502 a hubless
            // relay always returns.
            assertThat(status).contains("200");
        }
    }
}
