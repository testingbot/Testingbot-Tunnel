package com.testingbot.tunnel.proxy;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TunnelProxyHandlerTest {

    private TunnelProxyHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TunnelProxyHandler();
    }

    @Test
    void constructor_shouldCreateForwardProxyHandler() {
        assertThat(handler).isNotNull();
        assertThat(handler).isInstanceOf(org.eclipse.jetty.proxy.ProxyHandler.Forward.class);
    }

    @Test
    void lifecycle_startAndStopShouldNotThrow() throws Exception {
        // Replaces the old servlet init()/destroy() test: the handler owns an HttpClient
        // that is created on start and stopped on stop.
        Server server = new Server(0);
        server.setHandler(handler);

        assertThatCode(() -> {
            server.start();
            server.stop();
        }).doesNotThrowAnyException();
    }








    @Test
    void methodLabel_collapsesUnknownVerbs() {
        // Keeps Prometheus label cardinality bounded.
        assertThat(TunnelProxyHandler.methodLabel("get")).isEqualTo("GET");
        assertThat(TunnelProxyHandler.methodLabel("POST")).isEqualTo("POST");
        assertThat(TunnelProxyHandler.methodLabel("PROPFIND")).isEqualTo("OTHER");
        assertThat(TunnelProxyHandler.methodLabel(null)).isEqualTo("OTHER");
    }

    @Test
    void absoluteForm_bracketsIpv6OnceAndElidesDefaultPorts() {
        // HttpURI.getHost() keeps the brackets on an IPv6 literal, so bracketing again
        // produced "[[::1]]" and an unparseable request target.
        assertThat(TunnelProxyHandler.absoluteForm("http", "[::1]", 8080, "/x?a={json}"))
                .isEqualTo("http://[::1]:8080/x?a={json}");
        assertThat(TunnelProxyHandler.absoluteForm("http", "::1", 8080, "/x"))
                .isEqualTo("http://[::1]:8080/x");
        assertThat(TunnelProxyHandler.absoluteForm("http", "example.com", 80, "/"))
                .isEqualTo("http://example.com/");
        assertThat(TunnelProxyHandler.absoluteForm("https", "example.com", 443, "/"))
                .isEqualTo("https://example.com/");
        assertThat(TunnelProxyHandler.absoluteForm("http", "example.com", 8080, "/"))
                .isEqualTo("http://example.com:8080/");
    }
}
