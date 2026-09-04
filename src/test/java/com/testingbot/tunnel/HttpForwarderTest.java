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
    void stop_shouldStopForwarder() {
        // Given
        httpForwarder = new HttpForwarder(app);
        
        // When & Then
        assertThatCode(() -> httpForwarder.stop())
            .doesNotThrowAnyException();
    }
    
}