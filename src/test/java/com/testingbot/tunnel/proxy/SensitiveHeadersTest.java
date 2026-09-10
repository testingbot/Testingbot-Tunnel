package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveHeadersTest {

    @Test
    void isSensitive_isCaseInsensitive() {
        assertThat(SensitiveHeaders.isSensitive("Authorization")).isTrue();
        assertThat(SensitiveHeaders.isSensitive("AUTHORIZATION")).isTrue();
        assertThat(SensitiveHeaders.isSensitive("authorization")).isTrue();
    }

    @Test
    void isSensitive_recognizesKnownHeaders() {
        assertThat(SensitiveHeaders.isSensitive("Proxy-Authorization")).isTrue();
        assertThat(SensitiveHeaders.isSensitive("Cookie")).isTrue();
        assertThat(SensitiveHeaders.isSensitive("Set-Cookie")).isTrue();
        assertThat(SensitiveHeaders.isSensitive("X-Api-Key")).isTrue();
        assertThat(SensitiveHeaders.isSensitive("X-Auth-Token")).isTrue();
    }

    @Test
    void isSensitive_returnsFalseForOrdinaryHeaders() {
        assertThat(SensitiveHeaders.isSensitive("User-Agent")).isFalse();
        assertThat(SensitiveHeaders.isSensitive("Content-Type")).isFalse();
        assertThat(SensitiveHeaders.isSensitive("Host")).isFalse();
    }

    @Test
    void isSensitive_handlesNull() {
        assertThat(SensitiveHeaders.isSensitive(null)).isFalse();
    }

    @Test
    void redactValue_masksSensitiveValue() {
        assertThat(SensitiveHeaders.redactValue("Authorization", "Bearer secret"))
                .isEqualTo(SensitiveHeaders.REDACTED);
        assertThat(SensitiveHeaders.redactValue("Cookie", "sid=abc"))
                .isEqualTo(SensitiveHeaders.REDACTED);
    }

    @Test
    void redactValue_passesOrdinaryHeaderThrough() {
        assertThat(SensitiveHeaders.redactValue("User-Agent", "curl/8"))
                .isEqualTo("curl/8");
        assertThat(SensitiveHeaders.redactValue("Content-Type", "text/plain"))
                .isEqualTo("text/plain");
    }

    @Test
    void isSensitive_recognizesTheRelaysOwnCredentialHeader() {
        // The account key and secret, which the Selenium relay attaches to everything.
        assertThat(SensitiveHeaders.isSensitive("TB-Credentials")).isTrue();
    }

    @Test
    void redactUrl_masksCredentialShapedQueryValues() {
        assertThat(SensitiveHeaders.redactUrl("http://host/path?access_key=abc123&page=2"))
                .isEqualTo("http://host/path?access_key=" + SensitiveHeaders.REDACTED + "&page=2");
        assertThat(SensitiveHeaders.redactUrl("http://host/?token=t&apiKey=k"))
                .isEqualTo("http://host/?token=" + SensitiveHeaders.REDACTED
                        + "&apiKey=" + SensitiveHeaders.REDACTED);
    }

    @Test
    void redactUrl_masksUserInfo() {
        assertThat(SensitiveHeaders.redactUrl("http://user:hunter2@host/path"))
                .isEqualTo("http://" + SensitiveHeaders.REDACTED + "@host/path");
    }

    @Test
    void redactUrl_leavesOrdinaryTargetsAlone() {
        assertThat(SensitiveHeaders.redactUrl("http://host/path?page=2&q=hello"))
                .isEqualTo("http://host/path?page=2&q=hello");
        assertThat(SensitiveHeaders.redactUrl("host:443")).isEqualTo("host:443");
        assertThat(SensitiveHeaders.redactUrl(null)).isNull();
    }

    @Test
    void redactUrl_handlesTheLenientQueryStringsThisProxyForwards() {
        // Not routed through java.net.URI: these are exactly the targets it refuses, and a
        // target that failed to parse would then be logged with nothing removed.
        assertThat(SensitiveHeaders.redactUrl("http://host/?q={json}&secret=s"))
                .isEqualTo("http://host/?q={json}&secret=" + SensitiveHeaders.REDACTED);
        assertThat(SensitiveHeaders.redactUrl("http://host/?path=a[0]"))
                .isEqualTo("http://host/?path=a[0]");
    }

    @Test
    void redactUrl_keepsTheFragmentAfterTheQuery() {
        assertThat(SensitiveHeaders.redactUrl("http://host/?token=t#section"))
                .isEqualTo("http://host/?token=" + SensitiveHeaders.REDACTED + "#section");
    }
}
