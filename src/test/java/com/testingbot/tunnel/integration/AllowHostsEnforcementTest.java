package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.proxy.AllowedHosts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --allow-hosts} enforced on every way out of the tunnel.
 *
 * <p>A policy that covers two of three paths is not a policy, and that was the situation before:
 * WebsocketHandler sits outside CustomConnectHandler in the chain and intercepts upgrades before
 * they reach it, so it applied no destination checks at all. A {@code ws://} URL walked straight
 * past {@code --fast-fail-regexps} and {@code --localhost-policy deny} as well.
 */
class AllowHostsEnforcementTest {

    private HttpServer origin;
    /**
     * A real WebSocket origin.
     *
     * <p>The upgrade tests used to point at {@code origin}, a com.sun HttpServer, which cannot
     * upgrade -- it answered 500, and {@code doesNotContain("403")} is satisfied by a 500. So the
     * two tests that exist to prove the new check has not broken the relay were passing on a
     * failed upgrade, and would have kept passing had the relay been removed entirely.
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

    /**
     * An origin that completes one WebSocket handshake and then echoes bytes back.
     *
     * <p>Enough of the protocol to prove the relay is bidirectional after the 101: the frames are
     * never interpreted, only echoed, which is exactly what the tunnel does to them.
     */
    private void startWebsocketOrigin() throws Exception {
        wsPort = TestPorts.free();
        wsOrigin = new ServerSocket(wsPort, 50, InetAddress.getLoopbackAddress());
        wsThread = new Thread(() -> {
            while (!wsOrigin.isClosed()) {
                try (Socket client = wsOrigin.accept()) {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();
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

    private void start(String allowHosts) throws Exception {
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
        app.setAllowedHosts(AllowedHosts.parse(allowHosts));
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

    /** Sends a raw request through the proxy and returns everything that came back. */
    private String through(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                response.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                if (response.indexOf("\r\n\r\n") >= 0 && response.length() > 12) {
                    break;
                }
            }
            return response.toString();
        }
    }

    private String get(String host) throws Exception {
        return through("GET http://" + host + ":" + originPort + "/ HTTP/1.1\r\nHost: " + host
                + ":" + originPort + "\r\nConnection: close\r\n\r\n");
    }

    private String connect(String host) throws Exception {
        return through("CONNECT " + host + ":" + originPort + " HTTP/1.1\r\nHost: " + host
                + ":" + originPort + "\r\n\r\n");
    }

    private String upgradeRequest(String host, int port) {
        return "GET http://" + host + ":" + port + "/ws HTTP/1.1\r\nHost: " + host
                + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
    }

    private String upgrade(String host) throws Exception {
        return through(upgradeRequest(host, originPort));
    }

    /**
     * Upgrades against the echoing origin, then writes a byte and reads it back.
     *
     * @return the response head, followed by whatever came back after it
     */
    private String upgradeAndEcho(String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write(upgradeRequest(host, wsPort).getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            StringBuilder head = new StringBuilder();
            int c;
            while (head.indexOf("\r\n\r\n") < 0 && (c = in.read()) >= 0) {
                head.append((char) c);
            }
            if (head.indexOf("101") < 0) {
                return head.toString();
            }
            out.write("relayed".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] buffer = new byte[64];
            int n = in.read(buffer);
            return head + (n > 0 ? new String(buffer, 0, n, StandardCharsets.UTF_8) : "");
        }
    }

    @Test
    void aPermittedHostIsReachedOverPlainHttp() throws Exception {
        start("127.0.0.1");

        assertThat(get("127.0.0.1")).contains("reached");
    }

    @Test
    void anUnlistedHostIsRefusedOverPlainHttp() throws Exception {
        start("only.this.example");

        String response = get("127.0.0.1");
        assertThat(response).contains("403");
        assertThat(response).contains("not-allowed");
        assertThat(response).doesNotContain("reached");
    }

    @Test
    void anUnlistedHostIsRefusedOverConnect() throws Exception {
        start("only.this.example");

        assertThat(connect("127.0.0.1")).doesNotContain("200");
    }

    @Test
    void anUnlistedHostIsRefusedOverAWebsocketUpgrade() throws Exception {
        // The bypass: this handler applied no destination policy whatever, so ws:// reached
        // hosts that http:// and CONNECT to the same name were refused.
        start("only.this.example");

        String response = upgrade("127.0.0.1");
        assertThat(response).contains("403");
        assertThat(response).doesNotContain("101");
    }

    @Test
    void aPermittedHostStillUpgradesNormally() throws Exception {
        // The check must not break the feature it guards. Asserting only doesNotContain("403")
        // was satisfied by the 500 the non-upgradable origin returned, so this now needs a real
        // 101 and a byte relayed in each direction afterwards.
        start("127.0.0.1");
        startWebsocketOrigin();

        String response = upgradeAndEcho("127.0.0.1");

        assertThat(response).contains("101 Switching Protocols");
        assertThat(response).contains("Sec-WebSocket-Accept");
        assertThat(response).endsWith("relayed");
    }

    @Test
    void everyPathIsOpenWhenNothingIsConfigured() throws Exception {
        start(null);

        startWebsocketOrigin();

        assertThat(get("127.0.0.1")).contains("reached");
        assertThat(connect("127.0.0.1")).contains("200");
        assertThat(upgradeAndEcho("127.0.0.1")).contains("101").endsWith("relayed");
    }
}
