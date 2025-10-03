package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HttpForwarderTest {

    private App app;
    private HttpForwarder httpForwarder;
    
    @BeforeEach
    void setUp() {
        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
    }
    
    @Test
    void constructor_withValidApp_shouldCreateHttpForwarder() {
        // Given & When & Then
        assertThatCode(() -> {
            httpForwarder = new HttpForwarder(app);
        }).doesNotThrowAnyException();
        
        assertThat(httpForwarder).isNotNull();
        
        // Clean up
        httpForwarder.stop();
    }
    
    @Test
    void stop_shouldStopForwarder() {
        // Given
        httpForwarder = new HttpForwarder(app);
        
        // When & Then
        assertThatCode(() -> httpForwarder.stop())
            .doesNotThrowAnyException();
    }
    
    @Test
    void testForwarding_withRunningForwarder_shouldExecuteWithoutException() {
        // Given: HttpForwarder with running Jetty server
        httpForwarder = new HttpForwarder(app);

        // When: Testing if forwarder is responding
        // Then: Should not throw exception (result depends on SSH tunnel availability)
        assertThatCode(() -> httpForwarder.testForwarding())
            .doesNotThrowAnyException();

        // Clean up
        httpForwarder.stop();
    }
}