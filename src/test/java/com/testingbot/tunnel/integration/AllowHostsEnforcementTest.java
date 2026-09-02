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
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
    private HttpProxy httpProxy;
    private int proxyPort;
    private int originPort;

    @AfterEach
    void tearDown() {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (origin != null) {
            origin.stop(0);
        }
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

    private String upgrade(String host) throws Exception {
        return through("GET http://" + host + ":" + originPort + "/ws HTTP/1.1\r\nHost: " + host
                + ":" + originPort + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
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
        // The check must not break the feature it guards: a listed host is still relayed, and
        // reaches the origin, which answers a plain 404 for /ws rather than an upgrade.
        start("127.0.0.1");

        String response = upgrade("127.0.0.1");
        assertThat(response).doesNotContain("403");
    }

    @Test
    void everyPathIsOpenWhenNothingIsConfigured() throws Exception {
        start(null);

        assertThat(get("127.0.0.1")).contains("reached");
        assertThat(connect("127.0.0.1")).contains("200");
        assertThat(upgrade("127.0.0.1")).doesNotContain("403");
    }
}
