package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --fast-fail-regexps}, shared by the plain-HTTP and CONNECT paths.
 *
 * <p>The deny-only cases were previously covered against CustomConnectHandler; they moved here
 * with the logic, unchanged in meaning, so the exception syntax could be added in one place
 * rather than twice with a chance of the two paths diverging.
 */
class FastFailPolicyTest {

    private static FastFailPolicy of(String... patterns) {
        return FastFailPolicy.compile(patterns);
    }

    @Test
    void matchesLiteralHostname() {
        FastFailPolicy policy = of("evil\\.example\\.com");

        assertThat(policy.blocks("evil.example.com:443")).isTrue();
        assertThat(policy.blocks("ok.example.com:443")).isFalse();
    }

    @Test
    void matchesRegexPattern() {
        FastFailPolicy policy = of(".*\\.bad\\.com");

        assertThat(policy.blocks("foo.bad.com:443")).isTrue();
        assertThat(policy.blocks("bad.com:443")).isFalse();
    }

    @Test
    void stripsPortBeforeMatching() {
        assertThat(of("^evil\\.com$").blocks("evil.com:8443")).isTrue();
    }

    @Test
    void isCaseInsensitiveOnTheHost() {
        assertThat(of("evil\\.com").blocks("EVIL.COM:443")).isTrue();
    }

    @Test
    void emptyOrNullInputs_blockNothing() {
        assertThat(FastFailPolicy.none().blocks("anything.com:443")).isFalse();
        assertThat(of().blocks("anything.com:443")).isFalse();
        assertThat(of("  ", null).blocks("anything.com:443")).isFalse();
        assertThat(of(".*").blocks(null)).isFalse();
    }

    @Test
    void invalidRegex_isIgnoredRatherThanFatal() {
        // One bad entry must not take down a tunnel whose other patterns are fine.
        FastFailPolicy policy = of("[unclosed", "evil\\.com");

        assertThat(policy.blocks("evil.com")).isTrue();
        assertThat(policy.blocks("fine.com")).isFalse();
    }

    @Test
    void matchesBracketedIpv6Literals() {
        FastFailPolicy policy = of("::1");

        assertThat(policy.blocks("[::1]:443")).isTrue();
        assertThat(policy.blocks("[::1]")).isTrue();
    }

    @Test
    void doesNotTruncateABareIpv6Literal() {
        assertThat(of("^fe80::1$").blocks("fe80::1")).isTrue();
    }

    @Test
    void checksEveryDenyPattern_notJustTheFirst() {
        FastFailPolicy policy = of("nomatch\\.com", "alsonomatch\\.com", "blocked\\.com");

        assertThat(policy.blocks("blocked.com")).isTrue();
    }

    @Test
    void blankAndNullEntries_areSkipped() {
        FastFailPolicy policy = of("good\\.com", "  ", "[unclosed", null);

        assertThat(policy.blocks("good.com")).isTrue();
        assertThat(policy.blocks("other.com")).isFalse();
    }

    @Test
    void exception_allowsOneHostThroughACatchAllDeny() {
        // The case the deny-only list could not express: block everything but staging.
        FastFailPolicy policy = of(".*", "!(^|\\.)staging\\.example\\.com$");

        assertThat(policy.blocks("staging.example.com")).isFalse();
        assertThat(policy.blocks("api.staging.example.com")).isFalse();
        assertThat(policy.blocks("prod.example.com")).isTrue();
        assertThat(policy.blocks("anything.else")).isTrue();
    }

    @Test
    void exceptionsWin_regardlessOfOrder() {
        assertThat(of("!ok\\.com", "\\.com$").blocks("ok.com")).isFalse();
        assertThat(of("\\.com$", "!ok\\.com").blocks("ok.com")).isFalse();
    }

    @Test
    void exceptionsAlone_blockNothing() {
        // Nothing to be excepted from; must not become an accidental allow-list-only deny.
        assertThat(of("!ok\\.com").blocks("anything.com")).isFalse();
    }

    @Test
    void exceptionApplies_toTheNormalisedHost() {
        FastFailPolicy policy = of(".*", "!^staging\\.example\\.com$");

        assertThat(policy.blocks("STAGING.example.com:8443")).isFalse();
    }

    @Test
    void bangWithWhitespace_isStillAnException() {
        assertThat(of(".*", " ! ok\\.com ").blocks("ok.com")).isFalse();
    }

    @Test
    void aTrailingDotDoesNotWalkPastAPattern() {
        // The root label is legal in a URL and means the same name, so a deny list written for
        // example.com was bypassed by asking for example.com. instead.
        FastFailPolicy policy = FastFailPolicy.compile(new String[]{"example\\.com"});

        assertThat(policy.blocks("example.com")).isTrue();
        assertThat(policy.blocks("example.com.")).isTrue();
        assertThat(policy.blocks("example.com.:443")).isTrue();
    }
}
