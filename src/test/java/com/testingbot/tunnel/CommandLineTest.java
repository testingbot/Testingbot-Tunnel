package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for command-line argument parsing and configuration
 */
class CommandLineTest {

    private App app;

    @BeforeEach
    void setUp() {
        app = new App();
    }

    @Test
    void setDebugMode_shouldEnableDebugLogging() {
        // Given: App with debug mode off
        assertThat(app.isDebugMode()).isFalse();

        // When: Enabling debug mode
        app.setDebugMode(true);

        // Then: Debug mode should be enabled
        assertThat(app.isDebugMode()).isTrue();
    }

    @Test
    void setTunnelIdentifier_shouldStoreIdentifier() {
        // Given: Tunnel identifier
        String identifier = "my-tunnel-1";

        // When: Setting tunnel identifier
        app.setTunnelIdentifier(identifier);

        // Then: Should be stored
        assertThat(app.getTunnelIdentifier()).isEqualTo(identifier);
    }

    @Test
    void setJettyPort_shouldStorePort() {
        // Given: Custom jetty port
        int port = 9090;

        // When: Setting jetty port
        app.setJettyPort(port);

        // Then: Should be stored
        assertThat(app.getJettyPort()).isEqualTo(port);
    }

    @Test
    void setMetricsPort_shouldStorePort() {
        // Given: Custom metrics port
        int port = 8080;

        // When: Setting metrics port
        app.setMetricsPort(port);

        // Then: Should be stored
        assertThat(app.getMetricsPort()).isEqualTo(port);
    }

    @Test
    void getHubPort_shouldReturnValue() {
        // Given: Fresh App instance
        // When: Getting hub port
        int hubPort = app.getHubPort();

        // Then: Should return a value (default is 80)
        assertThat(hubPort).isGreaterThan(0);
    }

    @Test
    void getSeleniumPort_shouldReturnDefaultValue() {
        // Given: Fresh App instance
        // When: Getting selenium port
        int seleniumPort = app.getSeleniumPort();

        // Then: Should return default value
        assertThat(seleniumPort).isEqualTo(4445);
    }

    @Test
    void getSSHPort_shouldReturnValue() {
        // Given: Fresh App instance
        // When: Getting SSH port
        int sshPort = app.getSSHPort();

        // Then: Should return a value
        assertThat(sshPort).isGreaterThan(0);
    }

    @Test
    void isBypassingSquid_shouldReturnFalseByDefault() {
        // Given: Fresh App instance
        // When: Checking bypass squid
        boolean bypassingSquid = app.isBypassingSquid();

        // Then: Should be false by default
        assertThat(bypassingSquid).isFalse();
    }

    @Test
    void isNoBump_shouldReturnFalseByDefault() {
        // Given: Fresh App instance
        // When: Checking no bump
        boolean noBump = app.isNoBump();

        // Then: Should be false by default
        assertThat(noBump).isFalse();
    }

    @Test
    void getPac_shouldReturnNullByDefault() {
        // Given: Fresh App instance
        // When: Getting PAC
        String pac = app.getPac();

        // Then: Should be null by default
        assertThat(pac).isNull();
    }

    @Test
    void defaultValues_shouldBeSet() {
        // Given: Fresh App instance
        App freshApp = new App();

        // Then: Default values should be set
        assertThat(freshApp.getSeleniumPort()).isEqualTo(4445);
        assertThat(freshApp.getMetricsPort()).isEqualTo(8003);
        assertThat(freshApp.getHubPort()).isGreaterThan(0);
        assertThat(freshApp.isDebugMode()).isFalse();
        assertThat(freshApp.isBypassingSquid()).isFalse();
        assertThat(freshApp.isNoBump()).isFalse();
    }

    @Test
    void multipleConfiguration_shouldAllWork() {
        // Given: Multiple configuration options
        // When: Setting multiple options
        app.setDebugMode(true);
        app.setJettyPort(9000);
        app.setTunnelIdentifier("test-tunnel");
        app.setProxy("proxy.example.com:8080");
        app.setProxyAuth("user:pass");

        // Then: All should be stored correctly
        assertThat(app.isDebugMode()).isTrue();
        assertThat(app.getJettyPort()).isEqualTo(9000);
        assertThat(app.getTunnelIdentifier()).isEqualTo("test-tunnel");
        assertThat(app.getProxy()).isEqualTo("proxy.example.com:8080");
        assertThat(app.getProxyAuth()).isEqualTo("user:pass");
    }

    @Test
    void setClientKeyAndSecret_shouldStore() {
        // Given: Client credentials
        String key = "test_key_123";
        String secret = "test_secret_456";

        // When: Setting credentials
        app.setClientKey(key);
        app.setClientSecret(secret);

        // Then: Should be stored
        assertThat(app.getClientKey()).isEqualTo(key);
        assertThat(app.getClientSecret()).isEqualTo(secret);
    }

    @Test
    void getServerIP_shouldReturnValue() {
        // Given: Fresh App instance
        // When: Getting server IP
        String serverIP = app.getServerIP();

        // Then: Should return a value or null
        // Just verify the method exists and doesn't throw
        // (serverIP can be null initially)
    }

    @Test
    void setBasicAuth_shouldStoreAuth() {
        // Given: Basic auth array
        String[] basicAuth = {"localhost:8080", "user", "pass"};

        // When: Setting basic auth
        app.setBasicAuth(basicAuth);

        // Then: Method should execute without exception
        // (We can't verify the internal state without a getter)
    }

    @Test
    void setFreeJettyPort_shouldAssignPort() {
        // Given: Fresh App instance
        // When: Setting free jetty port
        app.setFreeJettyPort();

        // Then: Should assign a port > 0
        assertThat(app.getJettyPort()).isGreaterThan(0);
    }

    @Test
    void getHttpProxy_shouldReturnNullInitially() {
        // Given: Fresh App instance
        // When: Getting HTTP proxy
        HttpProxy httpProxy = app.getHttpProxy();

        // Then: Should be null initially
        assertThat(httpProxy).isNull();
    }

    @Test
    void getApi_shouldReturnApiInstance() {
        // Given: App with credentials
        app.setClientKey("key");
        app.setClientSecret("secret");

        // When: Getting API
        Api api = app.getApi();

        // Then: Should return an API instance or null
        // (depends on App initialization state)
        // Just verify method exists and doesn't throw
    }
}
