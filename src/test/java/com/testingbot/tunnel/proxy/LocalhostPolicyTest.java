package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code --localhost-policy}. */
class LocalhostPolicyTest {

    @Test
    void defaultsToAllow() {
        // The product's main use case is reaching a service on the developer's own machine,
        // so denying by default would break more than it protects.
        assertThat(LocalhostPolicy.parse(null)).isEqualTo(LocalhostPolicy.ALLOW);
        assertThat(LocalhostPolicy.parse("")).isEqualTo(LocalhostPolicy.ALLOW);
        assertThat(LocalhostPolicy.parse("allow")).isEqualTo(LocalhostPolicy.ALLOW);
        assertThat(LocalhostPolicy.parse("anything else")).isEqualTo(LocalhostPolicy.ALLOW);
    }

    @Test
    void parsesDenyCaseInsensitivelyAndTrimmed() {
        assertThat(LocalhostPolicy.parse("deny")).isEqualTo(LocalhostPolicy.DENY);
        assertThat(LocalhostPolicy.parse("  DENY  ")).isEqualTo(LocalhostPolicy.DENY);
    }

    @Test
    void allow_blocksNothing() {
        assertThat(LocalhostPolicy.ALLOW.blocks("localhost")).isFalse();
        assertThat(LocalhostPolicy.ALLOW.blocks("127.0.0.1:3000")).isFalse();
        assertThat(LocalhostPolicy.ALLOW.blocks("[::1]")).isFalse();
    }

    @Test
    void deny_blocksLoopbackInEveryShapeItArrives() {
        LocalhostPolicy deny = LocalhostPolicy.DENY;

        assertThat(deny.blocks("localhost")).isTrue();
        assertThat(deny.blocks("LOCALHOST:3000")).isTrue();
        assertThat(deny.blocks("app.localhost")).isTrue();
        assertThat(deny.blocks("127.0.0.1")).isTrue();
        assertThat(deny.blocks("127.0.0.1:8080")).isTrue();
        // The whole 127/8 range is loopback, not just .0.1.
        assertThat(deny.blocks("127.1.2.3")).isTrue();
        assertThat(deny.blocks("[::1]:443")).isTrue();
        // 0.0.0.0 reaches local services just as effectively.
        assertThat(deny.blocks("0.0.0.0")).isTrue();
    }

    @Test
    void deny_allowsOrdinaryDestinations() {
        LocalhostPolicy deny = LocalhostPolicy.DENY;

        assertThat(deny.blocks("staging.example.com")).isFalse();
        assertThat(deny.blocks("10.0.0.5:8080")).isFalse();
        assertThat(deny.blocks("192.168.1.10")).isFalse();
    }

    @Test
    void deny_leavesUnresolvableNamesToTheDialToReport() {
        // A name that cannot be resolved reaches nothing, so there is nothing to protect --
        // and a DNS error is a more useful answer than a policy violation.
        assertThat(LocalhostPolicy.DENY.blocks("no-such-host.invalid")).isFalse();
    }

    @Test
    void handlesNullAndEmptyTargets() {
        assertThat(LocalhostPolicy.DENY.blocks(null)).isFalse();
        assertThat(LocalhostPolicy.DENY.blocks("")).isFalse();
    }
}
