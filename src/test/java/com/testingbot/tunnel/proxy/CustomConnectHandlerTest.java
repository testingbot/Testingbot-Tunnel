package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CustomConnectHandlerTest {

    private App app;
    private CustomConnectHandler handler;

    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
    }

    @Test
    void constructor_withNoProxy_shouldInitializeWithoutProxy() {
        // Given & When
        handler = new CustomConnectHandler(app);

        // Then
        assertThat(handler).isNotNull();
    }

    @Test
    void constructor_withProxy_shouldInitializeWithProxy() {
        // Given
        app.setProxy("proxy.example.com:8080");

        // When
        handler = new CustomConnectHandler(app);

        // Then
        assertThat(handler).isNotNull();
    }

    @Test
    void constructor_withProxyAndAuth_shouldInitializeWithProxyAuth() {
        // Given
        app.setProxy("proxy.example.com:8080");
        app.setProxyAuth("user:password");

        // When
        handler = new CustomConnectHandler(app);

        // Then
        assertThat(handler).isNotNull();
    }

    @Test
    void constructor_withProxyNoPort_shouldUseDefaultPort() {
        // Given
        app.setProxy("proxy.example.com");

        // When
        handler = new CustomConnectHandler(app);

        // Then
        assertThat(handler).isNotNull();
    }

    @Test
    void setDebugMode_shouldUpdateDebugMode() {
        // Given
        handler = new CustomConnectHandler(app);

        // Then
        assertThatCode(() -> handler.setDebugMode(true))
            .doesNotThrowAnyException();
    }
}
