package com.testingbot.tunnel.integration;

import com.sun.net.httpserver.HttpServer;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --localhost-policy deny} enforced on the address actually dialled, on every path out.
 *
 * <p>The policy used to be decided on one resolution and the connection made on a second,
 * independent one, so the address that was judged was never the address that was connected to. A
 * name answering with a routable address when checked and loopback when dialled reached the
 * services the option exists to keep unreachable -- and with {@code --dns} set,
 * {@code CustomDnsResolver} disables caching outright, so the two lookups were independent by
 * construction rather than by luck.
 *
 * <p>DNS rebinding itself cannot be reproduced here without a fake resolver, and
 * {@code CustomDnsResolver} is final with a private constructor. {@code --connect-to} produces
 * the same split deterministically and through the real code: the name check judges
 * {@code internal.example}, which does not resolve at all and is therefore permitted ("nothing
 * to reach, nothing to protect"), and the dial then goes to loopback. Same divergence, same
 * defence, no DNS games -- and it is the reason the check has to live at the dial rather than at
 * the name.
 *
 * <p>Every assertion has a matching {@code ALLOW} case. A test that only checks something is
 * refused passes just as happily against a proxy that is broken for every destination.
 */
class LocalhostDialEnforcementTest {

    private static final String NAME = "internal.example";

    private HttpServer origin;
    /**
     * A real WebSocket origin, not the HttpServer above.
     *
     * <p>An HttpServer cannot upgrade: it answers 500, and {@code doesNotContain("101")} is
     * satisfied by a 500. Pointed there, the upgrade test passed with the dial check removed --
     * it was proving nothing about the check and would have kept passing had the relay been
     * deleted outright. The ALLOW control below is what makes the difference observable.
     */
    private ServerSocket wsOrigin;
    private Thread wsThread;
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;
    private int wsPort;

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (origin != null) {
            origin.stop(0);
        }
        if (wsOrigin != null && !wsOrigin.isClosed()) {
            try {
                wsOrigin.close();
            } catch (IOException ignored) {
                // closing on the way out
            }
        }
        if (wsThread != null) {
            wsThread.interrupt();
        }
    }

    /** An origin that completes one WebSocket handshake, so a 101 means the relay got through. */
    private void startWebsocketOrigin() throws Exception {
        wsPort = TestPorts.free();
        wsOrigin = new ServerSocket(wsPort, 50, InetAddress.getLoopbackAddress());
        wsThread = new Thread(() -> {
            while (!wsOrigin.isClosed()) {
                try (Socket client = wsOrigin.accept()) {
                    InputStream in = client.getInputStream();
                    java.io.OutputStream out = client.getOutputStream();
                    StringBuilder head = new StringBuilder();
                    int c;
                    while (head.indexOf("\r\n\r\n") < 0 && (c = in.read()) >= 0) {
                        head.append((char) c);
                    }
                    out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                            // The accept value for the fixed key the test sends.
                            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] buffer = new byte[512];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                } catch (IOException done) {
                    return;
                }
            }
        });
        wsThread.setDaemon(true);
        wsThread.start();
    }

    /** Starts loopback origins and a proxy whose --connect-to sends NAME to them. */
    private void start(String localhostPolicy) throws Exception {
        startWebsocketOrigin();

        originPort = TestPorts.free();
        origin = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), originPort), 0);
        origin.createContext("/", exchange -> {
            byte[] body = "reached".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();

        proxyPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setLocalhostPolicy(localhostPolicy);
        app.setConnectTo(new String[]{
            NAME + ":" + originPort + ":127.0.0.1:" + originPort,
            NAME + ":" + wsPort + ":127.0.0.1:" + wsPort});
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

    /**
     * Sends a raw request through the proxy and returns everything that came back.
     *
     * <p>The buffer lives outside the try because both endings are normal here and neither may
     * lose what already arrived: a refused dial can drop the connection, and a successful CONNECT
     * leaves the tunnel open with nothing further to send, so the read times out *after* the
     * "200 Connection established" that the assertion is about.
     */
    private String through(String request) throws Exception {
        StringBuilder response = new StringBuilder();
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                if (response.length() > 8192) {
                    break;
                }
            }
        } catch (IOException closedOrIdle) {
            // Keep whatever arrived; see above.
        }
        return response.toString();
    }

    private String get() throws Exception {
        return through("GET http://" + NAME + ":" + originPort + "/ HTTP/1.1\r\n"
                + "Host: " + NAME + ":" + originPort + "\r\nConnection: close\r\n\r\n");
    }

    private String connect() throws Exception {
        return through("CONNECT " + NAME + ":" + originPort + " HTTP/1.1\r\n"
                + "Host: " + NAME + ":" + originPort + "\r\n\r\n");
    }

    private String upgrade() throws Exception {
        return through("GET http://" + NAME + ":" + wsPort + "/ws HTTP/1.1\r\n"
                + "Host: " + NAME + ":" + wsPort + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n");
    }

    // ------------------------------------------------------------------ deny

    @Test
    void plainHttpDialToLoopbackIsRefused() throws Exception {
        start("deny");

        assertThat(get()).doesNotContain("reached");
    }

    @Test
    void connectDialToLoopbackIsRefused() throws Exception {
        start("deny");

        assertThat(connect()).doesNotContain("200");
    }

    @Test
    void websocketDialToLoopbackIsRefused() throws Exception {
        start("deny");

        assertThat(upgrade()).doesNotContain("101");
    }

    // ----------------------------------------------------------------- allow

    /**
     * The control. Without these, every "is refused" above would also pass against a proxy that
     * had simply stopped working, or against a --connect-to that never took effect.
     */
    @Test
    void plainHttpStillReachesLoopbackUnderAllow() throws Exception {
        start("allow");

        assertThat(get()).contains("reached");
    }

    @Test
    void connectStillReachesLoopbackUnderAllow() throws Exception {
        start("allow");

        assertThat(connect()).contains("200");
    }

    @Test
    void websocketStillReachesLoopbackUnderAllow() throws Exception {
        start("allow");

        assertThat(upgrade()).contains("101");
    }
}
