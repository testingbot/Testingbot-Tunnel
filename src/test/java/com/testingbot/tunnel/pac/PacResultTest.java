package com.testingbot.tunnel.pac;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing the {@code FindProxyForURL} return value into egress decisions. */
class PacResultTest {

    @Test
    void directIsRecognised() {
        PacResult result = PacResult.parse("DIRECT");

        assertThat(result.isDirect()).isTrue();
        assertThat(result.first().toProxySpec()).isNull();
    }

    @Test
    void proxyWithHostAndPort() {
        PacResult.Entry entry = PacResult.parse("PROXY proxy.corp:8080").first();

        assertThat(entry.getKind()).isEqualTo(PacResult.Kind.PROXY);
        assertThat(entry.getHost()).isEqualTo("proxy.corp");
        assertThat(entry.getPort()).isEqualTo(8080);
        assertThat(entry.toProxySpec()).isEqualTo("proxy.corp:8080");
    }

    @Test
    void socksBecomesASocks5Spec() {
        assertThat(PacResult.parse("SOCKS s.corp:1080").first().toProxySpec())
                .isEqualTo("socks5://s.corp:1080");
        assertThat(PacResult.parse("SOCKS5 s.corp:1080").first().toProxySpec())
                .isEqualTo("socks5://s.corp:1080");
    }

    @Test
    void failoverListKeepsItsOrder() {
        PacResult result = PacResult.parse("PROXY a:1; PROXY b:2; DIRECT");

        assertThat(result.getEntries()).hasSize(3);
        assertThat(result.first().getHost()).isEqualTo("a");
        assertThat(result.getEntries().get(2).isDirect()).isTrue();
        assertThat(result.isDirect()).isFalse();
    }

    @Test
    void whitespaceAndCaseAreTolerated() {
        // Real files are inconsistent about both.
        assertThat(PacResult.parse("  proxy   p.corp:3128  ;  direct ").getEntries()).hasSize(2);
        assertThat(PacResult.parse("proxy p.corp:3128").first().getHost()).isEqualTo("p.corp");
    }

    @Test
    void missingPortTakesTheSchemeDefault() {
        assertThat(PacResult.parse("PROXY p.corp").first().getPort()).isEqualTo(80);
        assertThat(PacResult.parse("SOCKS s.corp").first().getPort()).isEqualTo(1080);
    }

    @Test
    void emptyOrNullMeansDirect() {
        // What the specification and every browser do.
        assertThat(PacResult.parse(null).isDirect()).isTrue();
        assertThat(PacResult.parse("").isDirect()).isTrue();
        assertThat(PacResult.parse("   ").isDirect()).isTrue();
    }

    @Test
    void unusableEntriesAreDroppedRatherThanFailingTheWholeResult() {
        // One typo in a long file should not take the tunnel offline when the rest is usable.
        PacResult result = PacResult.parse("PROXY ; GARBAGE x; PROXY good:8080; PROXY bad:99999");

        assertThat(result.getEntries()).hasSize(1);
        assertThat(result.first().getHost()).isEqualTo("good");
    }

    @Test
    void httpsProxyIsRefusedRatherThanDowngraded() {
        // HTTPS means TLS to the proxy itself, which the egress paths do not implement.
        // Treating it as plain HTTP would silently send credentials in the clear.
        assertThat(PacResult.parse("HTTPS secure.corp:443").isDirect()).isTrue();
        assertThat(PacResult.parse("HTTPS secure.corp:443; PROXY p:8080").first().getHost())
                .isEqualTo("p");
    }

    @Test
    void ipv6LiteralKeepsItsColons() {
        PacResult.Entry entry = PacResult.parse("PROXY [2001:db8::1]:8080").first();

        assertThat(entry.getHost()).isEqualTo("[2001:db8::1]");
        assertThat(entry.getPort()).isEqualTo(8080);
    }

    @Test
    void everythingUnparseableFallsBackToDirect() {
        assertThat(PacResult.parse("NONSENSE").isDirect()).isTrue();
    }
}
