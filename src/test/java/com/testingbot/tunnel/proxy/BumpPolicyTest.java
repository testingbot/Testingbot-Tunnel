package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --nobump-domains}, the per-host form of {@code --nobump}.
 *
 * <p>The decision is acted on by TestingBot's Squid, so what this class owns is deciding what to
 * ask for and refusing to ask for something meaningless. An entry that cannot be a host name
 * would match nothing on the server and produce a tunnel that bumps when the user believed it
 * would not -- silence being the worst outcome for a flag whose effect is invisible from here.
 */
class BumpPolicyTest {

    @Test
    void hostsAreParsedTrimmedAndLowercased() {
        BumpPolicy policy = BumpPolicy.parse(false, " Staging.Example.COM , api.internal ");

        assertThat(policy.domains()).containsExactly("staging.example.com", "api.internal");
        assertThat(policy.apiValue()).isEqualTo("staging.example.com,api.internal");
        assertThat(policy.isConfigured()).isTrue();
        assertThat(policy.isAllDomains()).isFalse();
    }

    @Test
    void aRepeatedHostIsSentOnce() {
        BumpPolicy policy = BumpPolicy.parse(false, "a.example,a.example,b.example");

        assertThat(policy.domains()).containsExactly("a.example", "b.example");
    }

    @Test
    void emptyEntriesAreDropped() {
        // Trailing commas are the usual way this is written by a shell loop.
        assertThat(BumpPolicy.parse(false, "a.example,,").domains()).containsExactly("a.example");
        assertThat(BumpPolicy.parse(false, "").isConfigured()).isFalse();
        assertThat(BumpPolicy.parse(false, null).isConfigured()).isFalse();
    }

    @Test
    void nobumpMeansEveryHostAndSuppressesTheList() {
        // --nobump already covers everything, so a list beside it could only narrow what is
        // total. Sending both would leave the server to guess which was meant.
        BumpPolicy policy = BumpPolicy.parse(true, "staging.example.com");

        assertThat(policy.isAllDomains()).isTrue();
        assertThat(policy.apiValue())
                .as("nothing per-host should be sent alongside the global flag")
                .isNull();
        assertThat(policy.toString()).isEqualTo("all domains");
    }

    @Test
    void nothingConfiguredSendsNothing() {
        BumpPolicy policy = BumpPolicy.parse(false, null);

        assertThat(policy.apiValue()).isNull();
        assertThat(policy.isConfigured()).isFalse();
        assertThat(policy.toString()).isEqualTo("none");
    }

    @Test
    void aUrlIsRefusedRatherThanSentAsAHostName() {
        // The commonest way to write this wrongly. Sent as-is it would match nothing, and the
        // tunnel would bump the very host the user was trying to exempt.
        assertThatThrownBy(() -> BumpPolicy.parse(false, "https://staging.example.com/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a URL");
    }

    @Test
    void aPortIsRefused() {
        assertThatThrownBy(() -> BumpPolicy.parse(false, "staging.example.com:443"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not include a port");
    }

    @Test
    void whitespaceInsideAHostIsRefused() {
        assertThatThrownBy(() -> BumpPolicy.parse(false, "two words.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without spaces");
    }

    @Test
    void lineBreaksAreRefused() {
        // These reach the tunnel-creation request; keeping them out of the value is the point.
        assertThatThrownBy(() -> BumpPolicy.parse(false, "evil.example\r\nx: y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line breaks");
    }
}
