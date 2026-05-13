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
}
