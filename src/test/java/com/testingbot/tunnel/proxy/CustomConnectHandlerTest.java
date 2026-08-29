package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

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

    @Test
    void isSuccessfulConnect_acceptsHttp200() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 200 Connection Established")).isTrue();
    }

    @Test
    void isSuccessfulConnect_acceptsHttp10() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.0 200 OK")).isTrue();
    }

    @Test
    void isSuccessfulConnect_acceptsAny2xx() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 201 Created")).isTrue();
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 299 Custom")).isTrue();
    }

    @Test
    void isSuccessfulConnect_rejectsHttp407() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 407 Proxy Authentication Required")).isFalse();
    }

    @Test
    void isSuccessfulConnect_rejectsHttp502() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 502 Bad Gateway")).isFalse();
    }

    @Test
    void isSuccessfulConnect_rejectsBodyLineWith200Substring() {
        // The old buggy check would have accepted this because "200" is a substring.
        assertThat(CustomConnectHandler.isSuccessfulConnect("Content-Length: 200")).isFalse();
    }

    @Test
    void isSuccessfulConnect_rejectsMalformedStatusLine() {
        assertThat(CustomConnectHandler.isSuccessfulConnect("garbage")).isFalse();
        assertThat(CustomConnectHandler.isSuccessfulConnect("")).isFalse();
        assertThat(CustomConnectHandler.isSuccessfulConnect(null)).isFalse();
        assertThat(CustomConnectHandler.isSuccessfulConnect("HTTP/1.1 notanumber OK")).isFalse();
    }

    @Test
    void hostBlocked_matchesLiteralHostname() {
        List<Pattern> patterns = List.of(Pattern.compile("evil\\.example\\.com"));
        assertThat(CustomConnectHandler.hostBlocked("evil.example.com:443", patterns)).isTrue();
        assertThat(CustomConnectHandler.hostBlocked("ok.example.com:443", patterns)).isFalse();
    }

    @Test
    void hostBlocked_matchesRegexPattern() {
        List<Pattern> patterns = List.of(Pattern.compile(".*\\.bad\\.com"));
        assertThat(CustomConnectHandler.hostBlocked("foo.bad.com:443", patterns)).isTrue();
        assertThat(CustomConnectHandler.hostBlocked("bad.com:443", patterns)).isFalse();
    }

    @Test
    void hostBlocked_stripsPortBeforeMatching() {
        List<Pattern> patterns = List.of(Pattern.compile("^evil\\.com$"));
        assertThat(CustomConnectHandler.hostBlocked("evil.com:8443", patterns)).isTrue();
    }

    @Test
    void hostBlocked_caseInsensitive() {
        List<Pattern> patterns = List.of(Pattern.compile("evil\\.com"));
        assertThat(CustomConnectHandler.hostBlocked("EVIL.COM:443", patterns)).isTrue();
    }

    @Test
    void hostBlocked_returnsFalseForEmptyPatterns() {
        assertThat(CustomConnectHandler.hostBlocked("anything.com:443", Collections.emptyList())).isFalse();
    }

    @Test
    void hostBlocked_returnsFalseForNullHost() {
        List<Pattern> patterns = List.of(Pattern.compile(".*"));
        assertThat(CustomConnectHandler.hostBlocked(null, patterns)).isFalse();
    }

    @Test
    void setBlackList_silentlyIgnoresInvalidRegex() {
        handler = new CustomConnectHandler(app);
        // Should not throw on a regex with an unclosed group; just logs and drops it.
        assertThatCode(() -> handler.setBlackList(new String[]{"valid\\.com", "(unclosed"}))
            .doesNotThrowAnyException();
    }

    @Test
    void setBlackList_handlesNullAndEmpty() {
        handler = new CustomConnectHandler(app);
        assertThatCode(() -> handler.setBlackList(null)).doesNotThrowAnyException();
        assertThatCode(() -> handler.setBlackList(new String[]{})).doesNotThrowAnyException();
        assertThatCode(() -> handler.setBlackList(new String[]{"", null, "  "})).doesNotThrowAnyException();
    }

    @Test
    void validateDestination_rejectsBlacklistedHost() {
        // ConnectHandler consults validateDestination() before dialling out and answers 403
        // itself, so this is where the fast-fail policy has to bite.
        CustomConnectHandler handler = new CustomConnectHandler(new App());
        handler.setBlackList(new String[]{"blocked\\.example\\.com"});

        assertThat(handler.validateDestination("blocked.example.com", 443)).isFalse();
    }

    @Test
    void validateDestination_allowsOtherHosts() {
        CustomConnectHandler handler = new CustomConnectHandler(new App());
        handler.setBlackList(new String[]{"blocked\\.example\\.com"});

        assertThat(handler.validateDestination("allowed.example.com", 443)).isTrue();
    }

    @Test
    void validateDestination_allowsEverythingWhenNoBlacklist() {
        CustomConnectHandler handler = new CustomConnectHandler(new App());

        assertThat(handler.validateDestination("anything.example.com", 443)).isTrue();
    }

    @Test
    void hostBlocked_matchesBracketedIpv6Literals() {
        // "[::1]:443" used to be truncated at the first colon, leaving "[", so no pattern
        // could ever match an IPv6 destination and fast-fail silently let it through.
        List<Pattern> patterns = List.of(Pattern.compile("::1"));

        assertThat(CustomConnectHandler.hostBlocked("[::1]:443", patterns)).isTrue();
        assertThat(CustomConnectHandler.hostBlocked("[::1]", patterns)).isTrue();
    }

    @Test
    void hostBlocked_stripsThePortButNotABareIpv6Literal() {
        assertThat(CustomConnectHandler.hostBlocked("example.com:443",
                List.of(Pattern.compile("^example\\.com$")))).isTrue();
        // Unbracketed and multi-colon: a bare IPv6 literal, so nothing may be stripped.
        assertThat(CustomConnectHandler.hostBlocked("fe80::1",
                List.of(Pattern.compile("^fe80::1$")))).isTrue();
    }
}
