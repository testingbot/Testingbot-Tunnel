package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing and matching for curl-style {@code --connect-to} rules. */
class ConnectToMapTest {

    private static ConnectToMap of(String... entries) {
        return ConnectToMap.parse(entries);
    }

    @Test
    void remapsBothHostAndPort() {
        ConnectToMap map = of("prod.example.com:443:127.0.0.1:8443");

        ConnectToMap.Target target = map.remap("prod.example.com", 443);
        assertThat(target.host()).isEqualTo("127.0.0.1");
        assertThat(target.port()).isEqualTo(8443);
    }

    @Test
    void leavesNonMatchingDestinationsAlone() {
        ConnectToMap map = of("prod.example.com:443:127.0.0.1:8443");

        assertThat(map.remap("other.example.com", 443).host()).isEqualTo("other.example.com");
        // Same host, different port: the rule names a port, so it must not match.
        assertThat(map.remap("prod.example.com", 8080).port()).isEqualTo(8080);
    }

    @Test
    void emptySourceFields_matchAnything() {
        assertThat(of("::127.0.0.1:8443").remap("anything.com", 443).host()).isEqualTo("127.0.0.1");
        assertThat(of(":443:127.0.0.1:").remap("any.com", 443).host()).isEqualTo("127.0.0.1");
        assertThat(of(":443:127.0.0.1:").remap("any.com", 80).host()).isEqualTo("any.com");
    }

    @Test
    void emptyTargetFields_leaveThatHalfUnchanged() {
        // Port-only redirect: same host, different port.
        ConnectToMap.Target target = of("example.com:443::8443").remap("example.com", 443);
        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.port()).isEqualTo(8443);

        // Host-only redirect: same port.
        target = of("example.com:443:127.0.0.1:").remap("example.com", 443);
        assertThat(target.host()).isEqualTo("127.0.0.1");
        assertThat(target.port()).isEqualTo(443);
    }

    @Test
    void firstMatchingRuleWins() {
        ConnectToMap map = of("example.com:443:first:1111", "example.com:443:second:2222");

        assertThat(map.remap("example.com", 443).host()).isEqualTo("first");
    }

    @Test
    void matchingIsCaseInsensitiveAndIgnoresAnyPortSuffix() {
        ConnectToMap map = of("example.com:443:127.0.0.1:8443");

        assertThat(map.remap("EXAMPLE.COM", 443).host()).isEqualTo("127.0.0.1");
    }

    @Test
    void bracketedIpv6_survivesParsingOnBothSides() {
        // A plain split(":") would tear these apart and lose the four fields.
        ConnectToMap map = of("[2001:db8::1]:443:[::1]:8443");

        ConnectToMap.Target target = map.remap("[2001:db8::1]", 443);
        assertThat(target.host()).isEqualTo("::1");
        assertThat(target.port()).isEqualTo(8443);
    }

    @Test
    void malformedEntries_areIgnoredRatherThanFatal() {
        // Wrong field count, bad port, unbalanced bracket, and a rule that does nothing.
        assertThat(of("too:few:fields").isEmpty()).isTrue();
        assertThat(of("a:1:b:2:c:3").isEmpty()).isTrue();
        assertThat(of("a:notaport:b:2").isEmpty()).isTrue();
        assertThat(of("a:0:b:2").isEmpty()).isTrue();
        assertThat(of("a:1:b:70000").isEmpty()).isTrue();
        assertThat(of("[unclosed:1:b:2").isEmpty()).isTrue();
        assertThat(of(":::").isEmpty()).isTrue();
    }

    @Test
    void oneBadEntry_doesNotDiscardTheGoodOnes() {
        ConnectToMap map = of("nonsense", "example.com:443:127.0.0.1:8443");

        assertThat(map.remap("example.com", 443).host()).isEqualTo("127.0.0.1");
    }

    @Test
    void emptyInputs_remapNothing() {
        assertThat(ConnectToMap.none().isEmpty()).isTrue();
        assertThat(of().isEmpty()).isTrue();
        assertThat(of("", null).isEmpty()).isTrue();
        assertThat(ConnectToMap.none().remap("example.com", 443).host()).isEqualTo("example.com");
    }

    @Test
    void nullHost_isHandled() {
        assertThat(of("a:1:b:2").remap(null, 443).host()).isNull();
    }
}
