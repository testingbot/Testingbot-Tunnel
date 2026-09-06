package com.testingbot.tunnel.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.testingbot.tunnel.pac.PacPolicy;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --pac-local} applied to ordinary HTTP requests.
 *
 * <p>This path was wired but not implemented: {@code TunnelProxyHandler} had a
 * {@code setPacPolicy} that {@code HttpProxy} dutifully called and nothing ever read, so its
 * client only ever knew the static {@code --proxy}. CONNECT and WebSocket upgrades did consult
 * the file, which made routing depend on the scheme -- the same tunnel sent {@code https://}
 * where the PAC file said and {@code http://} somewhere else entirely.
 *
 * <p>It survived because the PAC coverage was all unit tests of the interpreter plus an e2e
 * scenario that only ran {@code --pac-test}, which evaluates the file and prints the answer
 * without proxying anything. So every test of "what does this PAC file say" passed while
 * "where does traffic actually go" was never asked on this path.
 */
class PacHttpRoutingTest {

    private WireMockServer viaProxy;
    private WireMockServer directOrigin;
    private WireMockServer proxiedOrigin;
    private Server localProxyServer;
    private int localProxyPort;

    @BeforeEach
    void setUp() {
        viaProxy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        viaProxy.start();
        directOrigin = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        directOrigin.start();
        proxiedOrigin = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        proxiedOrigin.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (localProxyServer != null && localProxyServer.isStarted()) {
            localProxyServer.stop();
        }
        for (WireMockServer server : new WireMockServer[]{viaProxy, directOrigin, proxiedOrigin}) {
            if (server != null && server.isRunning()) {
                server.stop();
            }
        }
    }

    private void startLocalProxy(String pacScript, String staticProxy) throws Exception {
        localProxyServer = new Server(0);
        TunnelProxyHandler handler = new TunnelProxyHandler();
        if (staticProxy != null) {
            handler.setUpstreamProxy(staticProxy, null);
        }
        handler.setPacPolicy(PacPolicy.of(pacScript, "test.pac"));
        localProxyServer.setHandler(handler);
        localProxyServer.start();
        localProxyPort = ((ServerConnector) localProxyServer.getConnectors()[0]).getLocalPort();
    }

    private int getThrough(String url) throws Exception {
        RequestConfig config = RequestConfig.custom()
                .setProxy(new HttpHost("http", "127.0.0.1", localProxyPort))
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build()) {
            return client.execute(new HttpGet(url), response -> response.getCode());
        }
    }

    @Test
    void aPacFileRoutesPlainHttpThroughTheProxyItNames() throws Exception {
        proxiedOrigin.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(200).withBody("origin")));
        viaProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().proxiedFrom("http://127.0.0.1:" + proxiedOrigin.port())));

        // Everything goes via the proxy. Before the fix this file was read, cached, and ignored.
        startLocalProxy("function FindProxyForURL(url, host) {"
                + " return \"PROXY 127.0.0.1:" + viaProxy.port() + "\"; }", null);

        assertThat(getThrough("http://127.0.0.1:" + proxiedOrigin.port() + "/thing")).isEqualTo(200);

        viaProxy.verify(getRequestedFor(urlPathEqualTo("/thing")));
    }

    @Test
    void aPacFileSendsDirectHostsDirect() throws Exception {
        directOrigin.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(200).withBody("origin")));
        viaProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().withStatus(500)));

        startLocalProxy("function FindProxyForURL(url, host) { return \"DIRECT\"; }", null);

        assertThat(getThrough("http://127.0.0.1:" + directOrigin.port() + "/thing")).isEqualTo(200);

        directOrigin.verify(getRequestedFor(urlPathEqualTo("/thing")));
        assertThat(viaProxy.getAllServeEvents()).isEmpty();
    }

    @Test
    void theDecisionIsPerDestinationRatherThanOncePerProcess() throws Exception {
        // The point of a PAC file, and the thing a single static ProxyConfiguration entry cannot
        // express: two origins in one run, one proxied and one direct.
        directOrigin.stubFor(get(urlPathEqualTo("/direct"))
                .willReturn(aResponse().withStatus(200)));
        proxiedOrigin.stubFor(get(urlPathEqualTo("/proxied"))
                .willReturn(aResponse().withStatus(200)));
        viaProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().proxiedFrom("http://127.0.0.1:" + proxiedOrigin.port())));

        startLocalProxy("function FindProxyForURL(url, host) {"
                + " if (url.indexOf(\"" + proxiedOrigin.port() + "\") >= 0)"
                + "   return \"PROXY 127.0.0.1:" + viaProxy.port() + "\";"
                + " return \"DIRECT\"; }", null);

        assertThat(getThrough("http://127.0.0.1:" + proxiedOrigin.port() + "/proxied")).isEqualTo(200);
        assertThat(getThrough("http://127.0.0.1:" + directOrigin.port() + "/direct")).isEqualTo(200);

        viaProxy.verify(getRequestedFor(urlPathEqualTo("/proxied")));
        viaProxy.verify(0, getRequestedFor(urlPathEqualTo("/direct")));
        directOrigin.verify(getRequestedFor(urlPathEqualTo("/direct")));
    }

    @Test
    void theStaticProxyCredentialIsNotSentToAPacChosenDestination() throws Exception {
        // --proxy-userpwd names one specific proxy. Once a PAC file decides routing, --proxy is
        // no longer the recipient, so its password must not be stamped on requests -- which for
        // a DIRECT answer means sending it straight to the origin, where it lands in an
        // arbitrary internet host's access log.
        directOrigin.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(200)));

        WireMockServer staticProxy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        staticProxy.start();
        try {
            TunnelProxyHandler handler = new TunnelProxyHandler();
            handler.setUpstreamProxy("127.0.0.1:" + staticProxy.port(), "user:sesame");
            handler.setPacPolicy(PacPolicy.of(
                    "function FindProxyForURL(url, host) { return \"DIRECT\"; }", "test.pac"));
            localProxyServer = new Server(0);
            localProxyServer.setHandler(handler);
            localProxyServer.start();
            localProxyPort = ((ServerConnector) localProxyServer.getConnectors()[0]).getLocalPort();

            assertThat(getThrough("http://127.0.0.1:" + directOrigin.port() + "/thing"))
                    .isEqualTo(200);

            directOrigin.verify(getRequestedFor(urlPathEqualTo("/thing"))
                    .withoutHeader("Proxy-Authorization"));
        } finally {
            staticProxy.stop();
        }
    }

    @Test
    void pacBeatsAStaticProxyForPlainHttpAsItDoesForConnect() throws Exception {
        // A --proxy registered alongside the PAC entries would match every origin and be chosen
        // ahead of them, which is the same "file is ignored" outcome by a different route.
        directOrigin.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(200)));

        WireMockServer staticProxy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        staticProxy.start();
        try {
            staticProxy.stubFor(any(urlMatching(".*")).willReturn(aResponse().withStatus(500)));

            startLocalProxy("function FindProxyForURL(url, host) { return \"DIRECT\"; }",
                    "127.0.0.1:" + staticProxy.port());

            assertThat(getThrough("http://127.0.0.1:" + directOrigin.port() + "/thing")).isEqualTo(200);
            assertThat(staticProxy.getAllServeEvents())
                    .as("--pac-local decides; --proxy is not consulted for a host it covers")
                    .isEmpty();
        } finally {
            staticProxy.stop();
        }
    }
}
