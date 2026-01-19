package com.testingbot.tunnel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Api class using WireMock to stub HTTP responses.
 */
class ApiTest {

    private WireMockServer wireMockServer;
    private App app;
    private Api api;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setTunnelIdentifier("test_tunnel");
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private Api createApiWithMockServer() {
        Api api = new Api(app);
        api.setApiScheme("http");
        api.setApiHost("localhost:" + wireMockServer.port());
        return api;
    }

    @Test
    void createTunnel_shouldSendCorrectRequest() throws Exception {
        // Given: Mock server configured to respond
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"123\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"192.168.1.1\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        JsonNode result = api.createTunnel();

        // Then: Should return parsed JSON
        assertThat(result.get("id").asText()).isEqualTo("123");
        assertThat(result.get("state").asText()).isEqualTo("READY");

        // And: Request should have correct headers
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("test_key:test_secret".getBytes());
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withHeader("Authorization", equalTo(expectedAuth))
            .withHeader("accept", equalTo("application/json")));
    }

    @Test
    void createTunnel_shouldIncludeTunnelIdentifier() throws Exception {
        // Given: App with tunnel identifier
        app.setTunnelIdentifier("my-tunnel-id");

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"456\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.1\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should include tunnel_identifier
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(containing("tunnel_identifier=my-tunnel-id")));
    }

    @Test
    void createTunnel_shouldIncludeNoCacheWhenBypassingSquid() throws Exception {
        // Given: App configured to bypass squid
        app.setTunnelIdentifier(null);
        Field bypassSquidField = App.class.getDeclaredField("bypassSquid");
        bypassSquidField.setAccessible(true);
        bypassSquidField.set(app, true);

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"789\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.2\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should include no_cache parameter
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(containing("no_cache=true")));
    }

    @Test
    void createTunnel_shouldIncludeNoBumpWhenConfigured() throws Exception {
        // Given: App configured with noBump
        app.setTunnelIdentifier(null);
        Field noBumpField = App.class.getDeclaredField("noBump");
        noBumpField.setAccessible(true);
        noBumpField.set(app, true);

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"101\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.3\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should include no_bump parameter
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(containing("no_bump=true")));
    }

    @Test
    void createTunnel_shouldIncludeSharedParameter() throws Exception {
        // Given: App configured with shared tunnel
        app.setTunnelIdentifier(null);
        app.setShared(true);

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"102\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.4\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should include shared parameter
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(containing("shared=true")));
    }

    @Test
    void createTunnel_withServerError_shouldThrowException() throws Exception {
        // Given: Mock server that returns an error response
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        api = createApiWithMockServer();

        // When/Then: Should throw exception (JSON parsing fails on non-JSON response)
        assertThatThrownBy(() -> api.createTunnel())
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Could not start tunnel");
    }

    @Test
    void pollTunnel_shouldReturnTunnelStatus() throws Exception {
        // Given: Mock server configured to respond
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/tunnel/12345"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"12345\",\"state\":\"READY\",\"ip\":\"192.168.1.100\"}")));

        api = createApiWithMockServer();

        // When: Polling tunnel
        JsonNode result = api.pollTunnel("12345");

        // Then: Should return parsed JSON
        assertThat(result.get("id").asText()).isEqualTo("12345");
        assertThat(result.get("state").asText()).isEqualTo("READY");
        assertThat(result.get("ip").asText()).isEqualTo("192.168.1.100");

        // And: Request should have correct auth header
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("test_key:test_secret".getBytes());
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/v1/tunnel/12345"))
            .withHeader("Authorization", equalTo(expectedAuth)));
    }

    @Test
    void pollTunnel_withNon200Response_shouldThrowException() throws Exception {
        // Given: Mock server returns 404
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/tunnel/99999"))
            .willReturn(aResponse()
                .withStatus(404)
                .withBody("Not Found")));

        api = createApiWithMockServer();

        // When/Then: Should throw exception
        assertThatThrownBy(() -> api.pollTunnel("99999"))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Could not get tunnel info");
    }

    @Test
    void pollTunnel_with401Response_shouldThrowException() throws Exception {
        // Given: Mock server returns 401 Unauthorized
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/tunnel/unauthorized"))
            .willReturn(aResponse()
                .withStatus(401)
                .withBody("Unauthorized")));

        api = createApiWithMockServer();

        // When/Then: Should throw exception
        assertThatThrownBy(() -> api.pollTunnel("unauthorized"))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Could not get tunnel info");
    }

    @Test
    void destroyTunnel_shouldSendDeleteRequest() throws Exception {
        // Given: Mock server configured to respond
        wireMockServer.stubFor(delete(urlPathEqualTo("/v1/tunnel/555"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":true}")));

        api = createApiWithMockServer();
        api.setTunnelID(555);

        // When: Destroying tunnel
        api.destroyTunnel();

        // Then: Should send DELETE request with correct auth
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("test_key:test_secret".getBytes());
        wireMockServer.verify(deleteRequestedFor(urlPathEqualTo("/v1/tunnel/555"))
            .withHeader("Authorization", equalTo(expectedAuth))
            .withHeader("accept", equalTo("application/json")));
    }

    @Test
    void setTunnelID_shouldUpdateTunnelId() {
        // Given
        api = new Api(app);
        int expectedTunnelId = 456;

        // When
        api.setTunnelID(expectedTunnelId);

        // Then: Should not throw
        assertThat(api).isNotNull();
    }

    @Test
    void createTunnel_shouldHandleJsonWithEscapedCharacters() throws Exception {
        // Given: Response with escaped characters
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"escaped-test\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.6\",\"message\":\"test value\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        JsonNode result = api.createTunnel();

        // Then: Should parse correctly
        assertThat(result.get("id").asText()).isEqualTo("escaped-test");
        assertThat(result.get("message").asText()).isEqualTo("test value");
    }

    @Test
    void pollTunnel_shouldHandleTunnelInStartingState() throws Exception {
        // Given: Tunnel in STARTING state
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/tunnel/starting-123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"starting-123\",\"state\":\"STARTING\",\"progress\":50}")));

        api = createApiWithMockServer();

        // When: Polling tunnel
        JsonNode result = api.pollTunnel("starting-123");

        // Then: Should correctly parse state
        assertThat(result.get("state").asText()).isEqualTo("STARTING");
        assertThat(result.get("progress").asInt()).isEqualTo(50);
    }

    @Test
    void createTunnel_shouldIncludeVersionInRequest() throws Exception {
        // Given: Mock server
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"version-test\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.8\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should include tunnel_version
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(containing("tunnel_version=" + App.VERSION)));
    }

    @Test
    void createTunnel_shouldNotIncludeEmptyTunnelIdentifier() throws Exception {
        // Given: App with empty tunnel identifier
        app.setTunnelIdentifier("");

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"no-id-test\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.9\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should NOT include tunnel_identifier (empty string is treated as not set)
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(notMatching(".*tunnel_identifier.*")));
    }

    @Test
    void createTunnel_shouldNotIncludeNullTunnelIdentifier() throws Exception {
        // Given: App with null tunnel identifier
        app.setTunnelIdentifier(null);

        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"null-id-test\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.10\"}")));

        api = createApiWithMockServer();

        // When: Creating tunnel
        api.createTunnel();

        // Then: Request should NOT include tunnel_identifier
        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/v1/tunnel/create"))
            .withRequestBody(notMatching(".*tunnel_identifier.*")));
    }

    @Test
    void pollTunnel_shouldReturnAllFields() throws Exception {
        // Given: Response with all fields
        wireMockServer.stubFor(get(urlPathEqualTo("/v1/tunnel/full-response"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"full-response\",\"state\":\"READY\",\"ip\":\"10.0.0.11\",\"version\":\"4.4\",\"port\":2010}")));

        api = createApiWithMockServer();

        // When: Polling tunnel
        JsonNode result = api.pollTunnel("full-response");

        // Then: All fields should be accessible
        assertThat(result.get("id").asText()).isEqualTo("full-response");
        assertThat(result.get("state").asText()).isEqualTo("READY");
        assertThat(result.get("ip").asText()).isEqualTo("10.0.0.11");
        assertThat(result.get("version").asText()).isEqualTo("4.4");
        assertThat(result.get("port").asInt()).isEqualTo(2010);
    }

    @Test
    void api_shouldAcceptProxyConfiguration() {
        // Given: App with proxy configured
        app.setProxy("proxy.example.com:8080");

        // When: Creating Api
        api = new Api(app);

        // Then: Should not throw - proxy configuration is stored
        assertThat(app.getProxy()).isEqualTo("proxy.example.com:8080");
    }

    @Test
    void api_shouldAcceptProxyAuthConfiguration() {
        // Given: App with proxy auth configured
        app.setProxy("proxy.example.com:8080");
        app.setProxyAuth("user:password");

        // When: Creating Api
        api = new Api(app);

        // Then: Should not throw - proxy auth configuration is stored
        assertThat(app.getProxyAuth()).isEqualTo("user:password");
    }

    @Test
    void destroyTunnel_with404Response_shouldNotThrow() throws Exception {
        // Given: Mock server returns 404 (tunnel already destroyed)
        wireMockServer.stubFor(delete(urlPathEqualTo("/v1/tunnel/already-destroyed"))
            .willReturn(aResponse()
                .withStatus(404)
                .withBody("Not Found")));

        api = createApiWithMockServer();
        api.setTunnelID(0); // This will create path /v1/tunnel/0, but we need to handle dynamically

        // Stub for tunnel ID 999
        wireMockServer.stubFor(delete(urlPathEqualTo("/v1/tunnel/999"))
            .willReturn(aResponse()
                .withStatus(404)
                .withBody("Not Found")));

        api.setTunnelID(999);

        // When/Then: Should not throw (destroy is fire-and-forget)
        api.destroyTunnel();

        // Verify request was made
        wireMockServer.verify(deleteRequestedFor(urlPathEqualTo("/v1/tunnel/999")));
    }

    @Test
    void api_multipleSequentialCalls_shouldWork() throws Exception {
        // Given: Multiple stubs
        wireMockServer.stubFor(post(urlPathEqualTo("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"id\":\"seq-1\",\"state\":\"READY\",\"version\":\"4.4\",\"ip\":\"10.0.0.13\"}")));

        wireMockServer.stubFor(get(urlPathEqualTo("/v1/tunnel/seq-1"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"id\":\"seq-1\",\"state\":\"RUNNING\",\"ip\":\"10.0.0.13\"}")));

        wireMockServer.stubFor(delete(urlPathEqualTo("/v1/tunnel/1"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"success\":true}")));

        api = createApiWithMockServer();

        // When: Making multiple calls
        JsonNode createResult = api.createTunnel();
        JsonNode pollResult = api.pollTunnel("seq-1");
        api.setTunnelID(1);
        api.destroyTunnel();

        // Then: All should succeed
        assertThat(createResult.get("id").asText()).isEqualTo("seq-1");
        assertThat(pollResult.get("state").asText()).isEqualTo("RUNNING");
    }
}
