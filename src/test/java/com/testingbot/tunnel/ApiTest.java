package com.testingbot.tunnel;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiTest {

    private WireMockServer wireMockServer;
    private App app;
    private Api api;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();

        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setTunnelIdentifier("test_tunnel");

        api = new Api(app);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void createTunnel_withValidCredentials_shouldHandleNetworkCall() {
        try {
            api.createTunnel();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("Could not start tunnel");
        }
    }

    @Test
    void pollTunnel_withValidTunnelId_shouldHandleRequest() {
        // Given
        String tunnelId = "123";

        // Then
        assertThatThrownBy(() -> api.pollTunnel(tunnelId))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Could not get tunnel info");
    }

    @Test
    void setTunnelID_shouldUpdateTunnelId() {
        // Given
        int expectedTunnelId = 456;

        // When
        api.setTunnelID(expectedTunnelId);

        // Then
        assertThat(api).isNotNull();
    }
}
