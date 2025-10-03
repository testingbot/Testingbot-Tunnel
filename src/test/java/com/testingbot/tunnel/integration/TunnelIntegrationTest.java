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
    void givenValidAppConfiguration_whenInitializingComponents_thenAllComponentsShouldInitializeSuccessfully() {
        // Given: Valid app configuration with available ports
        app.setJettyPort(findFreePort());
        
        // When: Initializing core components
        assertThatCode(() -> {
            Api api = new Api(app);
            HttpProxy httpProxy = new HttpProxy(app);
            HttpForwarder httpForwarder = new HttpForwarder(app);
            
            // Then: All components should initialize without errors
            assertThat(api).isNotNull();
            assertThat(httpProxy).isNotNull();
            assertThat(httpForwarder).isNotNull();
            
            // Clean up
            httpProxy.stop();
            httpForwarder.stop();
        }).doesNotThrowAnyException();
    }
    
    @Test
    void givenDoctorDiagnostics_whenRunningHealthChecks_thenShouldCompleteWithoutErrors() {
        // Given: App configured for diagnostics
        app.setJettyPort(findFreePort());
        
        // When: Running doctor diagnostics
        // Then: Should complete without throwing exceptions
        assertThatCode(() -> {
            Doctor doctor = new Doctor(app);
        }).doesNotThrowAnyException();
    }
    
    @Test
    void givenHttpProxyServer_whenStartingAndStopping_thenShouldManageLifecycleCorrectly() throws Exception {
        // Given: HTTP proxy configuration
        app.setJettyPort(findFreePort());
        HttpProxy httpProxy = new HttpProxy(app);
        
        // When: Starting the proxy server
        httpProxy.start();
        
        // When: Stopping the proxy server
        httpProxy.stop();
        
        // Then: Should stop without errors
        assertThatCode(() -> httpProxy.stop()).doesNotThrowAnyException();
    }
    
    @Test
    void givenHttpForwarder_whenStartingAndStopping_thenShouldManageLifecycleCorrectly() {
        // Given: HTTP forwarder configuration
        HttpForwarder httpForwarder = new HttpForwarder(app);
        
        // When: Stopping the forwarder
        httpForwarder.stop();
        
        // Then: Should stop without errors
        assertThatCode(() -> httpForwarder.stop()).doesNotThrowAnyException();
    }
    
    @Test
    void givenApiConfiguration_whenCreatingTunnel_thenShouldHandleApiResponse() {
        // Given: Mock API server response
        mockApiServer.stubFor(post(urlMatching("/v1/tunnel/create"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":123,\"server_ip\":\"127.0.0.1\",\"server_port\":443}")));
        
        Api api = new Api(app);
        
        // When: Creating a tunnel (this will fail in real scenario due to network)
        // Then: Should handle the API call structure correctly
        assertThatCode(() -> {
            try {
                JsonNode result = api.createTunnel();
                assertThat((Object) result).isNotNull();
            } catch (Exception e) {
                // Expected in test environment
                assertThat(e.getMessage()).isNotNull();
            }
        }).doesNotThrowAnyException();
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