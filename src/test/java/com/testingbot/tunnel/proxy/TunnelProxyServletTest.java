package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

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

    @Test
    void hostMatchesAny_matchesLiteralHostname() {
        List<Pattern> patterns = List.of(Pattern.compile("blocked\\.example\\.com"));
        assertThat(TunnelProxyServlet.hostMatchesAny("blocked.example.com", patterns)).isTrue();
        assertThat(TunnelProxyServlet.hostMatchesAny("safe.example.com", patterns)).isFalse();
    }

    @Test
    void hostMatchesAny_matchesRegex() {
        List<Pattern> patterns = List.of(Pattern.compile("^.*\\.internal$"));
        assertThat(TunnelProxyServlet.hostMatchesAny("api.internal", patterns)).isTrue();
        assertThat(TunnelProxyServlet.hostMatchesAny("public.example.com", patterns)).isFalse();
    }

    @Test
    void hostMatchesAny_returnsFalseForNullHost() {
        List<Pattern> patterns = List.of(Pattern.compile(".*"));
        assertThat(TunnelProxyServlet.hostMatchesAny(null, patterns)).isFalse();
    }

    @Test
    void hostMatchesAny_returnsFalseForEmptyPatterns() {
        assertThat(TunnelProxyServlet.hostMatchesAny("anything.com", Collections.emptyList())).isFalse();
    }

    @Test
    void hostMatchesAny_iteratesUntilMatch() {
        List<Pattern> patterns = List.of(
            Pattern.compile("never-matches\\.com"),
            Pattern.compile("also-never\\.com"),
            Pattern.compile("blocked\\.com")
        );
        assertThat(TunnelProxyServlet.hostMatchesAny("blocked.com", patterns)).isTrue();
    }
}
