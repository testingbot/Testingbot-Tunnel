package com.testingbot.tunnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void start_whenPortAlreadyBound_throwsHttpProxyStartException() throws Exception {
        // Bound on the address the proxy itself binds. A wildcard blocker does not collide with
        // a loopback bind on BSD-derived systems, where SO_REUSEADDR admits the more specific
        // address -- so the port would still be free for the proxy and the test would prove
        // nothing about how it reports one that is not.
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new java.net.InetSocketAddress(app.getBindAddress(), 0));
            app.setJettyPort(blocker.getLocalPort());
            assertThatThrownBy(() -> new HttpProxy(app))
                .isInstanceOf(HttpProxy.HttpProxyStartException.class)
                .hasMessageContaining(Integer.toString(blocker.getLocalPort()));
        }
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 8087; // fallback to default
        }
    }
}