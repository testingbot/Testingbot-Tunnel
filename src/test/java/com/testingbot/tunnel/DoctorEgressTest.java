package com.testingbot.tunnel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --doctor} has to test the route the tunnel actually takes.
 *
 * <p>It built its own HTTP client, and that client had drifted from the one {@link Api} uses: it
 * skipped SOCKS5 entirely, and it never supplied credentials for an authenticated proxy. So on
 * exactly the networks {@code --doctor} exists to diagnose it tested something else -- reporting
 * "can not be reached" for a tunnel that would have started, or reaching the API by a path the
 * tunnel would not have used and calling that a pass.
 *
 * <p>Asserted through the shared builder rather than by running the whole doctor, because what
 * changed is which client is built; running the checks would need the real TestingBot endpoints.
 */
class DoctorEgressTest {

    private WireMockServer proxy;

    @BeforeEach
    void setUp() {
        proxy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        proxy.start();
    }

    @AfterEach
    void tearDown() {
        if (proxy != null && proxy.isRunning()) {
            proxy.stop();
        }
    }

    private App appWithProxy(String spec, String auth) {
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy(spec);
        if (auth != null) {
            app.setProxyAuth(auth);
        }
        return app;
    }

    @Test
    void theConnectivityCheckGoesThroughAnAuthenticatedProxy() throws Exception {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("user:sesame".getBytes(StandardCharsets.UTF_8));
        proxy.stubFor(any(urlMatching(".*"))
                .withHeader("Proxy-Authorization", equalTo(expected))
                .willReturn(aResponse().withStatus(200)));
        proxy.stubFor(any(urlMatching(".*"))
                .withHeader("Proxy-Authorization", absent())
                .willReturn(aResponse().withStatus(407)
                        .withHeader("Proxy-Authenticate", "Basic realm=\"t\"")));

        App app = appWithProxy("127.0.0.1:" + proxy.port(), "user:sesame");

        HttpClientBuilder builder = Api.controlPlaneBuilder(app);
        try (CloseableHttpClient client = builder.build()) {
            int status = client.execute(new HttpHead("http://example.com/"),
                    response -> response.getCode());

            // Without credentials this is a 407, which checkConnection counts as unreachable --
            // so --doctor failed on a network where the tunnel works.
            assertThat(status)
                    .as("--doctor must authenticate to the proxy the way the tunnel does")
                    .isEqualTo(200);
        }
    }

    @Test
    void aSocks5ProxyIsActuallyUsedRatherThanIgnored() throws Exception {
        // The old code tested `spec != null && !spec.isSocks5()`, so a SOCKS5 --proxy produced a
        // plain direct client. Proven here by pointing SOCKS at a dead port while the target is
        // perfectly reachable directly: if the request succeeds, the proxy was ignored and the
        // check bypassed the only egress the tunnel has.
        proxy.stubFor(any(urlMatching(".*")).willReturn(aResponse().withStatus(200)));
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        App app = appWithProxy("socks5://127.0.0.1:" + deadPort, null);

        try (CloseableHttpClient client = Api.controlPlaneBuilder(app).build()) {
            assertThatThrownBy(() -> client.execute(
                    new HttpHead("http://127.0.0.1:" + proxy.port() + "/"),
                    response -> response.getCode()))
                    .as("a SOCKS5 --proxy must be dialled; going direct would have succeeded")
                    .isInstanceOf(java.io.IOException.class);
        }

        assertThat(proxy.getAllServeEvents())
                .as("nothing should have reached the target directly")
                .isEmpty();
    }
}
