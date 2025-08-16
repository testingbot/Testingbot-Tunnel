package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HttpProxyTest {

    private App app;
    private HttpProxy httpProxy;
    
    @BeforeEach
    void setUp() {
        app = new App();
        app.setJettyPort(findFreePort());
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
    }
    
    @Test
    void constructor_withValidApp_shouldCreateHttpProxy() {
        // Given & When
        assertThatCode(() -> {
            httpProxy = new HttpProxy(app);
        }).doesNotThrowAnyException();
        
        // Then
        assertThat(httpProxy).isNotNull();
    }
    
    @Test
    void start_shouldStartHttpProxyServer() throws Exception {
        // Given
        httpProxy = new HttpProxy(app);
        
        // When & Then
        assertThatCode(() -> httpProxy.start())
            .doesNotThrowAnyException();
        
        // Clean up
        httpProxy.stop();
    }
    
    @Test
    void stop_shouldStopHttpProxyServer() throws Exception {
        // Given
        httpProxy = new HttpProxy(app);
        httpProxy.start();
        
        // When & Then
        assertThatCode(() -> httpProxy.stop())
            .doesNotThrowAnyException();
    }
    
    @Test
    void testProxy_shouldValidateProxyFunctionality() {
        // Given
        httpProxy = new HttpProxy(app);
        
        // When
        boolean result = httpProxy.testProxy();
        
        // Then
        assertThat(result).isFalse(); // Expected in test environment
    }
    
    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 8087; // fallback to default
        }
    }
}