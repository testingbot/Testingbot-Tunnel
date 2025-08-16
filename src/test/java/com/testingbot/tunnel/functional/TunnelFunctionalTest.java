package com.testingbot.tunnel.functional;

import com.testingbot.tunnel.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TunnelFunctionalTest {

    private App app;

    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
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

    @Test
    void givenCustomHeaders_whenAddingHeaders_thenShouldStoreAllHeaders() {
        // Given: Multiple custom headers to add
        String[] headerPairs = {"X-Test:value1", "X-Another:value2", "Authorization:Bearer token"};

        // When: Adding each header
        for (String pair : headerPairs) {
            String[] parts = pair.split(":", 2);
            app.addCustomHeader(parts[0], parts[1]);
        }

        // Then: All headers should be stored correctly
        assertThat(app.getCustomHeaders()).hasSize(3);
        assertThat(app.getCustomHeaders().get("X-Test")).isEqualTo("value1");
        assertThat(app.getCustomHeaders().get("X-Another")).isEqualTo("value2");
        assertThat(app.getCustomHeaders().get("Authorization")).isEqualTo("Bearer token");
    }


    @Test
    void givenProxyConfiguration_whenConfiguringUpstreamProxy_thenShouldStoreConfiguration() {
        // Given: Proxy configuration details
        String proxyHost = "proxy.company.com:8080";
        String proxyAuth = "user:password";

        // When: Configuring proxy settings
        app.setProxy(proxyHost);
        app.setProxyAuth(proxyAuth);

        // Then: Configuration should be stored correctly
        assertThat(app.getProxy()).isEqualTo(proxyHost);
        assertThat(app.getProxyAuth()).isEqualTo(proxyAuth);
    }

    @Test
    void givenBasicAuthConfiguration_whenConfiguringHostAuth_thenShouldStoreCredentials() {
        // Given: Basic authentication configuration for specific hosts
        String[] authConfigs = {"localhost:8080:user1:pass1", "example.com:443:user2:pass2"};

        // When: Setting basic authentication
        app.setBasicAuth(authConfigs);

        // Then: Authentication configuration should be stored
        assertThat(app.getBasicAuth()).hasSize(2);
        assertThat(app.getBasicAuth()[0]).isEqualTo("localhost:8080:user1:pass1");
        assertThat(app.getBasicAuth()[1]).isEqualTo("example.com:443:user2:pass2");
    }

    @Test
    void givenDebugModeEnabled_whenLoggingConfiguration_thenShouldEnableVerboseLogging() {
        // Given: Debug mode requirements
        boolean debugEnabled = true;

        // When: Enabling debug mode
        app.setDebugMode(debugEnabled);

        // Then: Debug mode should be active
        assertThat(app.isDebugMode()).isTrue();
    }

    @Test
    void givenTunnelIdentifier_whenSettingIdentifier_thenShouldAllowTunnelIdentification() {
        // Given: Tunnel identifier for multiple tunnel support
        String identifier = "my-test-tunnel-123";

        // When: Setting tunnel identifier
        app.setTunnelIdentifier(identifier);

        // Then: Identifier should be stored for tunnel identification
        assertThat(app.getTunnelIdentifier()).isEqualTo(identifier);
    }
}
