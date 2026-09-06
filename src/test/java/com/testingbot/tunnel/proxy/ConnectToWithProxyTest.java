package com.testingbot.tunnel.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

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
 * {@code --connect-to} when an upstream proxy is also configured.
 *
 * <p>The rule describes where a named *destination* lives. With a proxy in play the socket goes
 * to the proxy and the destination travels in the request line, so there is nothing at dial time
 * for the rule to apply to -- but it was applied anyway, to the only endpoint being dialled: the
 * proxy. A wildcard entry therefore redirected the proxy connection itself and left the
 * destination exactly as it was, which is close to the opposite of what was asked for.
 *
 * <p>{@code ConnectToMapTest} covers the mapping itself and the existing dial tests cover direct
 * connections, so neither could see this: it only appears where the two features meet.
 */
class ConnectToWithProxyTest {

    private WireMockServer upstreamProxy;
    private WireMockServer origin;
    private WireMockServer decoy;
    private Server localProxyServer;
    private int localProxyPort;

    @BeforeEach
    void setUp() {
        upstreamProxy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamProxy.start();
        origin = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        origin.start();
        decoy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        decoy.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (localProxyServer != null && localProxyServer.isStarted()) {
            localProxyServer.stop();
        }
        for (WireMockServer server : new WireMockServer[]{upstreamProxy, origin, decoy}) {
            if (server != null && server.isRunning()) {
                server.stop();
            }
        }
    }

    private void startLocalProxy(String upstream, String... connectTo) throws Exception {
        localProxyServer = new Server(0);
        TunnelProxyHandler handler = new TunnelProxyHandler();
        if (upstream != null) {
            handler.setUpstreamProxy(upstream, null);
        }
        handler.setConnectTo(ConnectToMap.parse(connectTo));
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
    void aWildcardRuleDoesNotDivertTheUpstreamProxyItself() throws Exception {
        origin.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(200)));
        upstreamProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().proxiedFrom("http://127.0.0.1:" + origin.port())));
        decoy.stubFor(any(urlMatching(".*")).willReturn(aResponse().withStatus(418)));

        // "::host:port" -- empty HOST1 and PORT1, so it matches anything, as curl allows.
        // Applied to the proxy endpoint this sent the proxy connection to the decoy.
        startLocalProxy("127.0.0.1:" + upstreamProxy.port(),
                "::127.0.0.1:" + decoy.port());

        assertThat(getThrough("http://127.0.0.1:" + origin.port() + "/thing")).isEqualTo(200);

        upstreamProxy.verify(getRequestedFor(urlPathEqualTo("/thing")));
        assertThat(decoy.getAllServeEvents())
                .as("the proxy connection must not be remapped")
                .isEmpty();
    }

    @Test
    void aRuleNamingTheProxysAddressStillDoesNotMoveIt() throws Exception {
        // The proxy happens to share an address with a rule's HOST1:PORT1. It is still a proxy
        // endpoint, not a destination, so the rule does not apply to this socket.
        origin.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(200)));
        upstreamProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().proxiedFrom("http://127.0.0.1:" + origin.port())));
        decoy.stubFor(any(urlMatching(".*")).willReturn(aResponse().withStatus(418)));

        startLocalProxy("127.0.0.1:" + upstreamProxy.port(),
                "127.0.0.1:" + upstreamProxy.port() + ":127.0.0.1:" + decoy.port());

        assertThat(getThrough("http://127.0.0.1:" + origin.port() + "/thing")).isEqualTo(200);

        upstreamProxy.verify(getRequestedFor(urlPathEqualTo("/thing")));
        assertThat(decoy.getAllServeEvents()).isEmpty();
    }

    @Test
    void withoutAProxyTheDestinationIsStillRemapped() throws Exception {
        // The feature itself, unchanged: this is the case the existing tests cover, kept here so
        // a fix that simply stopped honouring --connect-to would not pass.
        decoy.stubFor(get(urlPathEqualTo("/thing"))
                .willReturn(aResponse().withStatus(418)));

        startLocalProxy(null, "127.0.0.1:" + origin.port() + ":127.0.0.1:" + decoy.port());

        assertThat(getThrough("http://127.0.0.1:" + origin.port() + "/thing")).isEqualTo(418);

        decoy.verify(getRequestedFor(urlPathEqualTo("/thing")));
        assertThat(origin.getAllServeEvents()).isEmpty();
    }
}
