package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxySpecTest {

    @Test
    void bareHostPort_defaultsToHttp() {
        // The historical --proxy form must keep working unchanged.
        ProxySpec spec = ProxySpec.parse("proxy.example.com:8080");
        assertThat(spec).isNotNull();
        assertThat(spec.getType()).isEqualTo(ProxySpec.Type.HTTP);
        assertThat(spec.getHost()).isEqualTo("proxy.example.com");
        assertThat(spec.getPort()).isEqualTo(8080);
        assertThat(spec.isSocks5()).isFalse();
    }

    @Test
    void explicitHttpScheme_isAccepted() {
        ProxySpec spec = ProxySpec.parse("http://proxy:3128");
        assertThat(spec.getType()).isEqualTo(ProxySpec.Type.HTTP);
        assertThat(spec.getPort()).isEqualTo(3128);
    }

    @Test
    void socks5Schemes_areRecognised() {
        for (String scheme : new String[]{"socks5", "socks5h", "socks", "SOCKS5"}) {
            ProxySpec spec = ProxySpec.parse(scheme + "://socks.example:1080");
            assertThat(spec).as("scheme %s", scheme).isNotNull();
            assertThat(spec.isSocks5()).as("scheme %s", scheme).isTrue();
            assertThat(spec.getHost()).isEqualTo("socks.example");
            assertThat(spec.getPort()).isEqualTo(1080);
        }
    }

    @Test
    void malformedValues_returnNull() {
        assertThat(ProxySpec.parse(null)).isNull();
        assertThat(ProxySpec.parse("")).isNull();
        assertThat(ProxySpec.parse("   ")).isNull();
        assertThat(ProxySpec.parse("no-port")).isNull();
        assertThat(ProxySpec.parse("host:")).isNull();
        assertThat(ProxySpec.parse(":8080")).isNull();
        assertThat(ProxySpec.parse("host:not-a-number")).isNull();
        assertThat(ProxySpec.parse("host:0")).isNull();
        assertThat(ProxySpec.parse("host:70000")).isNull();
        assertThat(ProxySpec.parse("ftp://host:21")).isNull();
    }

    @Test
    void ipv6LiteralUsesLastColonAsSeparator() {
        ProxySpec spec = ProxySpec.parse("socks5://[::1]:1080");
        assertThat(spec).isNotNull();
        assertThat(spec.getHost()).isEqualTo("[::1]");
        assertThat(spec.getPort()).isEqualTo(1080);
    }

    @Test
    void toString_roundTripsTheScheme() {
        assertThat(ProxySpec.parse("host:8080")).hasToString("http://host:8080");
        assertThat(ProxySpec.parse("socks5://host:1080")).hasToString("socks5://host:1080");
    }
}
