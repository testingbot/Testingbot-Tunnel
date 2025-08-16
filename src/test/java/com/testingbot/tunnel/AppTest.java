package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AppTest {

    private App app;

    @BeforeEach
    void setUp() {
        app = new App();
    }

    @Test
    void defaultValues_shouldBeSetCorrectly() {
        // Given & When & Then
        assertThat(app.getSeleniumPort()).isEqualTo(4445);
        assertThat(app.getJettyPort()).isEqualTo(0);
        assertThat(app.getTunnelID()).isEqualTo(0);
        assertThat(app.getHubPort()).isEqualTo(80);
        assertThat(app.getMetricsPort()).isEqualTo(8003);
        assertThat(app.isBypassingSquid()).isFalse();
        assertThat(app.isNoBump()).isFalse();
        assertThat(app.isDebugMode()).isFalse();
    }

    @Test
    void setClientKey_shouldUpdateClientKey() {
        // Given
        String expectedKey = "test_client_key";

        // When
        app.setClientKey(expectedKey);

        // Then
        assertThat(app.getClientKey()).isEqualTo(expectedKey);
    }

    @Test
    void setClientSecret_shouldUpdateClientSecret() {
        // Given
        String expectedSecret = "test_client_secret";

        // When
        app.setClientSecret(expectedSecret);

        // Then
        assertThat(app.getClientSecret()).isEqualTo(expectedSecret);
    }


    @Test
    void setJettyPort_shouldUpdateJettyPort() {
        // Given
        int expectedPort = 9090;

        // When
        app.setJettyPort(expectedPort);

        // Then
        assertThat(app.getJettyPort()).isEqualTo(expectedPort);
    }

    @Test
    void setTunnelIdentifier_shouldUpdateTunnelIdentifier() {
        // Given
        String expectedIdentifier = "my_test_tunnel";

        // When
        app.setTunnelIdentifier(expectedIdentifier);

        // Then
        assertThat(app.getTunnelIdentifier()).isEqualTo(expectedIdentifier);
    }

    @Test
    void setDebugMode_shouldUpdateDebugMode() {
        // Given & When
        app.setDebugMode(true);

        // Then
        assertThat(app.isDebugMode()).isTrue();
    }

    @Test
    void setFreeJettyPort_shouldFindAvailablePort() {
        // Given
        app.setJettyPort(0);

        // When
        app.setFreeJettyPort();

        // Then
        assertThat(app.getJettyPort()).isGreaterThan(0);
    }

    @Test
    void addCustomHeader_shouldStoreHeader() {
        // Given
        String headerName = "X-Test-Header";
        String headerValue = "test-value";

        // When
        app.addCustomHeader(headerName, headerValue);

        // Then
        assertThat(app.getCustomHeaders()).containsEntry(headerName, headerValue);
    }

    @Test
    void setProxy_shouldUpdateProxySettings() {
        // Given
        String proxyConfig = "proxy.example.com:8080";

        // When
        app.setProxy(proxyConfig);

        // Then
        assertThat(app.getProxy()).isEqualTo(proxyConfig);
    }
}
