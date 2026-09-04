package com.testingbot.tunnel.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.testingbot.tunnel.Api;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.Doctor;
import com.testingbot.tunnel.HttpForwarder;
import com.testingbot.tunnel.HttpProxy;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TunnelIntegrationTest {

    private WireMockServer mockApiServer;
    private App app;
    
    @BeforeEach
    void setUp() {
        mockApiServer = new WireMockServer(options().port(0));
        mockApiServer.start();
        
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setFreeJettyPort();
    }
    
    @AfterEach
    void tearDown() {
        if (mockApiServer != null) {
            mockApiServer.stop();
        }
    }
    
    
    
    
    
    
    @Test
    void givenPortConfiguration_whenFindingFreePort_thenShouldReturnAvailablePort() {
        // Given: App instance needing a free port
        app.setJettyPort(0);
        
        // When: Finding a free Jetty port
        app.setFreeJettyPort();
        
        // Then: Should have assigned a valid port number
        assertThat(app.getJettyPort()).isGreaterThan(0);
        assertThat(app.getJettyPort()).isLessThan(65536);
    }
    
    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }
}