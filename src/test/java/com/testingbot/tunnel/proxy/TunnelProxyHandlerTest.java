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
    void hostMatchesAny_matchesLiteralHostname() {
        List<Pattern> patterns = List.of(Pattern.compile("blocked\\.example\\.com"));
        assertThat(TunnelProxyHandler.hostMatchesAny("blocked.example.com", patterns)).isTrue();
        assertThat(TunnelProxyHandler.hostMatchesAny("safe.example.com", patterns)).isFalse();
    }

    @Test
    void hostMatchesAny_matchesRegex() {
        List<Pattern> patterns = List.of(Pattern.compile(".*\\.internal$"));
        assertThat(TunnelProxyHandler.hostMatchesAny("api.internal", patterns)).isTrue();
        assertThat(TunnelProxyHandler.hostMatchesAny("public.example.com", patterns)).isFalse();
    }

    @Test
    void hostMatchesAny_returnsFalseForNullHost() {
        List<Pattern> patterns = List.of(Pattern.compile(".*"));
        assertThat(TunnelProxyHandler.hostMatchesAny(null, patterns)).isFalse();
    }

    @Test
    void hostMatchesAny_returnsFalseForEmptyPatterns() {
        assertThat(TunnelProxyHandler.hostMatchesAny("anything.com", Collections.emptyList())).isFalse();
    }

    @Test
    void hostMatchesAny_iteratesUntilMatch() {
        List<Pattern> patterns = List.of(
                Pattern.compile("nomatch\\.com"),
                Pattern.compile("alsonomatch\\.com"),
                Pattern.compile("blocked\\.com"));
        assertThat(TunnelProxyHandler.hostMatchesAny("blocked.com", patterns)).isTrue();
    }

    @Test
    void compilePatterns_skipsInvalidAndBlankEntries() {
        // A bad regex from --fast-fail-regexps must not take the whole tunnel down.
        List<Pattern> patterns = TunnelProxyHandler.compilePatterns(
                new String[]{"good\\.com", "  ", "[unclosed", null});

        assertThat(patterns).hasSize(1);
        assertThat(TunnelProxyHandler.hostMatchesAny("good.com", patterns)).isTrue();
    }

    @Test
    void compilePatterns_emptyInputYieldsNoPatterns() {
        assertThat(TunnelProxyHandler.compilePatterns(null)).isEmpty();
        assertThat(TunnelProxyHandler.compilePatterns(new String[0])).isEmpty();
    }

    @Test
    void methodLabel_collapsesUnknownVerbs() {
        // Keeps Prometheus label cardinality bounded.
        assertThat(TunnelProxyHandler.methodLabel("get")).isEqualTo("GET");
        assertThat(TunnelProxyHandler.methodLabel("POST")).isEqualTo("POST");
        assertThat(TunnelProxyHandler.methodLabel("PROPFIND")).isEqualTo("OTHER");
        assertThat(TunnelProxyHandler.methodLabel(null)).isEqualTo("OTHER");
    }
}
