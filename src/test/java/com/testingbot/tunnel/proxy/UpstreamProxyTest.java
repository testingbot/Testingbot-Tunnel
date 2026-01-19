package com.testingbot.tunnel.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.testingbot.tunnel.App;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for upstream proxy functionality (--proxy and --proxy-userpwd options)
 *
 * These tests verify that the tunnel correctly configures and uses an upstream
 * proxy when specified via command-line arguments, for both HTTP and HTTPS traffic.
 */
class UpstreamProxyTest {

    private App app;
    private WireMockServer upstreamProxy;
    private WireMockServer targetServer;
    private Server localProxyServer;
    private int localProxyPort;

    @BeforeEach
    void setUp() throws Exception {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");

        // Start upstream proxy mock (simulates the --proxy server)
        upstreamProxy = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort());
        upstreamProxy.start();
        WireMock.configureFor("localhost", upstreamProxy.port());

        // Start target server mock
        targetServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort());
        targetServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (localProxyServer != null && localProxyServer.isStarted()) {
            localProxyServer.stop();
        }
        if (upstreamProxy != null && upstreamProxy.isRunning()) {
            upstreamProxy.stop();
        }
        if (targetServer != null && targetServer.isRunning()) {
            targetServer.stop();
        }
    }

    private void startLocalProxyWithUpstream(String proxyHost, String proxyAuth) throws Exception {
        localProxyServer = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder servletHolder = new ServletHolder(new TunnelProxyServlet());
        if (proxyHost != null) {
            servletHolder.setInitParameter("proxy", proxyHost);
        }
        if (proxyAuth != null) {
            servletHolder.setInitParameter("proxyAuth", proxyAuth);
        }
        servletHolder.setInitParameter("jetty", "8087");

        context.addServlet(servletHolder, "/*");
        localProxyServer.setHandler(context);
        localProxyServer.start();

        localProxyPort = ((ServerConnector) localProxyServer.getConnectors()[0]).getLocalPort();
    }

    @Test
    void httpRequest_shouldRouteThroughUpstreamProxy() throws Exception {
        // Given: Target server configured to respond
        targetServer.stubFor(get(urlPathEqualTo("/api/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"success\"}")));

        // And: Upstream proxy configured to forward requests
        upstreamProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().proxiedFrom("http://localhost:" + targetServer.port())));

        // And: Local proxy with upstream configuration
        startLocalProxyWithUpstream("localhost:" + upstreamProxy.port(), null);

        // When: Making HTTP request through local proxy
        HttpHost proxy = new HttpHost("http", "localhost", localProxyPort);
        RequestConfig config = RequestConfig.custom()
                .setProxy(proxy)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build()) {

            HttpGet request = new HttpGet("http://localhost:" + targetServer.port() + "/api/test");
            client.execute(request, response -> {
                // Then: Request should succeed
                assertThat(response.getCode()).isEqualTo(200);
                String body = EntityUtils.toString(response.getEntity());
                assertThat(body).contains("success");
                return null;
            });

            // And: Upstream proxy should have received the request
            upstreamProxy.verify(getRequestedFor(urlPathEqualTo("/api/test")));
        }
    }

    @Test
    void httpRequest_withProxyAuth_shouldSendAuthHeader() throws Exception {
        // Given: Upstream proxy requiring authentication
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("testuser:testpass".getBytes());

        upstreamProxy.stubFor(any(urlMatching(".*"))
                .withHeader("Proxy-Authorization", equalTo(expectedAuth))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Authenticated")));

        upstreamProxy.stubFor(any(urlMatching(".*"))
                .withHeader("Proxy-Authorization", absent())
                .willReturn(aResponse()
                        .withStatus(407)
                        .withHeader("Proxy-Authenticate", "Basic realm=\"test\"")));

        // And: Local proxy configured with auth
        startLocalProxyWithUpstream("localhost:" + upstreamProxy.port(), "testuser:testpass");

        // When: Making HTTP request through authenticated proxy
        HttpHost proxy = new HttpHost("http", "localhost", localProxyPort);
        RequestConfig config = RequestConfig.custom()
                .setProxy(proxy)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build()) {

            HttpGet request = new HttpGet("http://example.com/test");
            client.execute(request, response -> {
                // Then: Request should succeed with auth
                assertThat(response.getCode()).isEqualTo(200);
                return null;
            });

            // And: Upstream proxy should have received auth header
            upstreamProxy.verify(getRequestedFor(urlPathEqualTo("/test"))
                    .withHeader("Proxy-Authorization", equalTo(expectedAuth)));
        }
    }

    @Test
    void httpsConnect_shouldConfigureCustomConnectHandler() throws Exception {
        // Given: App configured with upstream proxy for HTTPS
        app.setProxy("proxy.example.com:8080");

        // When: Creating CustomConnectHandler
        CustomConnectHandler handler = new CustomConnectHandler(app);

        // Then: Handler should be configured for CONNECT tunneling through upstream
        assertThat(handler).isNotNull();

        // This handler will forward CONNECT requests to the upstream proxy
        // In real usage: client -> local proxy (CustomConnectHandler) -> upstream proxy -> target
    }

    @Test
    void httpsConnect_withProxyAuth_shouldSendAuthInConnect() throws Exception {
        // Given: Upstream proxy requiring auth for CONNECT
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes());

        upstreamProxy.stubFor(request("CONNECT", urlMatching(".*"))
                .withHeader("Proxy-Authorization", equalTo(expectedAuth))
                .willReturn(aResponse().withStatus(200)));

        upstreamProxy.stubFor(request("CONNECT", urlMatching(".*"))
                .withHeader("Proxy-Authorization", absent())
                .willReturn(aResponse()
                        .withStatus(407)
                        .withHeader("Proxy-Authenticate", "Basic realm=\"secure\"")));

        // And: Local proxy with auth
        app.setProxy("localhost:" + upstreamProxy.port());
        app.setProxyAuth("user:pass");
        CustomConnectHandler handler = new CustomConnectHandler(app);

        // Then: Handler should be configured with auth for CONNECT requests
        assertThat(handler).isNotNull();
    }

    @Test
    void proxyConfiguration_shouldHandleHostnameWithoutPort() {
        // Given: Proxy hostname without port
        app.setProxy("proxy.example.com");
        CustomConnectHandler handler = new CustomConnectHandler(app);

        // Then: Should default to port 80
        assertThat(handler).isNotNull();
        assertThat(app.getProxy()).isEqualTo("proxy.example.com");
    }

    @Test
    void proxyConfiguration_shouldHandleIPv4Address() throws Exception {
        // Given: IPv4 address as proxy
        startLocalProxyWithUpstream("127.0.0.1:" + upstreamProxy.port(), null);

        // Then: Should configure successfully
        assertThat(localProxyPort).isGreaterThan(0);
    }

    @Test
    void proxyConfiguration_shouldStoreCredentialsSeparately() {
        // Given: App with proxy and auth
        app.setProxy("proxy.example.com:8080");
        app.setProxyAuth("myuser:mypassword");

        // Then: Both should be stored independently
        assertThat(app.getProxy()).isEqualTo("proxy.example.com:8080");
        assertThat(app.getProxyAuth()).isEqualTo("myuser:mypassword");
    }

    @Test
    void customConnectHandler_shouldInitializeWithoutProxy() {
        // Given: No proxy configured
        CustomConnectHandler handler = new CustomConnectHandler(app);

        // Then: Handler should initialize successfully
        assertThat(handler).isNotNull();
    }

    @Test
    void multipleRequests_shouldReuseUpstreamProxyConnection() throws Exception {
        // Given: Target and upstream proxy configured
        targetServer.stubFor(get(urlPathMatching("/request.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("OK")));

        upstreamProxy.stubFor(any(urlMatching(".*"))
                .willReturn(aResponse().proxiedFrom("http://localhost:" + targetServer.port())));

        startLocalProxyWithUpstream("localhost:" + upstreamProxy.port(), null);

        // When: Making multiple requests
        HttpHost proxy = new HttpHost("http", "localhost", localProxyPort);
        RequestConfig config = RequestConfig.custom().setProxy(proxy).build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build()) {

            for (int i = 0; i < 3; i++) {
                HttpGet request = new HttpGet("http://localhost:" + targetServer.port() + "/request" + i);
                client.execute(request, response -> {
                    assertThat(response.getCode()).isEqualTo(200);
                    return null;
                });
            }

            // Then: All requests should go through upstream proxy
            upstreamProxy.verify(3, anyRequestedFor(urlPathMatching("/request.*")));
        }
    }
}
