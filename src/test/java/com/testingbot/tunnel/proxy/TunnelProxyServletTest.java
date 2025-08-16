package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TunnelProxyServletTest {

    private TunnelProxyServlet servlet;

    @BeforeEach
    void setUp() {
        servlet = new TunnelProxyServlet();
    }

    @Test
    void constructor_shouldCreateServlet() {
        assertThat(servlet).isNotNull();
        assertThat(servlet).isInstanceOf(org.eclipse.jetty.proxy.AsyncProxyServlet.class);
    }

    @Test
    void createNewInstance_shouldCreateServlet() {
        // Given
        TunnelProxyServlet newServlet = new TunnelProxyServlet();

        // Then
        assertThat(newServlet).isNotNull();
        assertThat(newServlet).isInstanceOf(org.eclipse.jetty.proxy.AsyncProxyServlet.class);
    }

    @Test
    void destroy_shouldCleanupResources() {
        assertThatCode(() -> servlet.destroy())
            .doesNotThrowAnyException();
    }
}
