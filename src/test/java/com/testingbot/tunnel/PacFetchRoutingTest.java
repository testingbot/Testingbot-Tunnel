package com.testingbot.tunnel;

import com.sun.net.httpserver.HttpServer;
import com.testingbot.tunnel.pac.PacPolicy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fetching a remote {@code --pac-local} document through the network's own egress rules.
 *
 * <p>The fetch was a bare {@code HttpURLConnection}, so it honoured neither {@code --proxy} nor
 * {@code --cacert-file}. On a proxy-only network the PAC URL was simply unreachable; on a
 * TLS-intercepting network -- the entire reason {@code --cacert-file} exists -- an {@code https}
 * PAC URL failed the handshake against a CA the JVM has never seen. In both cases the tunnel
 * refused to start over a document it had been told how to reach.
 */
class PacFetchRoutingTest {

    private static final String SCRIPT =
            "function FindProxyForURL(url, host) { return \"DIRECT\"; }";

    @Test
    void thePacDocumentIsFetchedThroughTheConfiguredProxy() throws Exception {
        List<String> proxiedRequests = new CopyOnWriteArrayList<>();

        // Stands in for the upstream proxy: an absolute-form request arrives here rather than
        // at the origin, which is exactly how we can tell the fetch was routed.
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxiedRequests.add(exchange.getRequestURI().toString());
            byte[] body = SCRIPT.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        proxy.start();

        try {
            // A port with nothing on it: if the fetch went direct rather than through the
            // proxy, it could only fail, so this cannot pass by accident.
            String url = "http://127.0.0.1:" + unusedPort() + "/proxy.pac";

            PacPolicy policy = PacPolicy.load(url,
                    sha256Of(SCRIPT),
                    new PacPolicy.FetchOptions(
                            new Proxy(Proxy.Type.HTTP,
                                    new InetSocketAddress("127.0.0.1",
                                            proxy.getAddress().getPort())),
                            null));

            assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();
            assertThat(proxiedRequests)
                    .as("the PAC fetch must go through --proxy, not around it")
                    .isNotEmpty();
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void aDirectFetchStillWorksWhenNothingIsConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy.pac", exchange -> {
            byte[] body = SCRIPT.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            PacPolicy policy = PacPolicy.load(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy.pac",
                    sha256Of(SCRIPT),
                    null);

            assertThat(policy.resolve("http://a.corp/", "a.corp").isDirect()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appDerivesFetchOptionsFromTheProxyOption() {
        // The wiring, not the mechanism: getPacPolicy() has to pass these on, or the fix is
        // present and unreachable.
        App app = new App();
        assertThat(app.pacFetchOptions())
                .as("nothing configured means nothing to pass")
                .isNull();

        app.setProxy("proxy.corp:3128");
        PacPolicy.FetchOptions options = app.pacFetchOptions();

        assertThat(options).isNotNull();
        assertThat(options.proxy()).isNotNull();
        assertThat(options.proxy().type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(options.proxy().address().toString()).contains("proxy.corp", "3128");
    }

    @Test
    void aSocks5ProxyBecomesASocksProxyForTheFetch() {
        App app = new App();
        app.setProxy("socks5://socks.corp:1080");

        PacPolicy.FetchOptions options = app.pacFetchOptions();

        assertThat(options).isNotNull();
        assertThat(options.proxy().type())
                .as("a SOCKS proxy is not an HTTP proxy; using the wrong type fails the fetch")
                .isEqualTo(Proxy.Type.SOCKS);
    }

    /**
     * Computed here rather than through PacPolicy.sha256Hex: an independent implementation, so
     * the pin actually checks the fetched bytes instead of agreeing with itself.
     */
    private static String sha256Of(String text) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static int unusedPort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
