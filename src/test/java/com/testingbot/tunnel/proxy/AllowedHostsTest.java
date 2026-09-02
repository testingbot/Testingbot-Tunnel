package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --allow-hosts}, the inverse of {@code --fast-fail-regexps}.
 *
 * <p>A deny list only helps for destinations somebody thought of in advance. For a tunnel into a
 * corporate network the answerable question is which hosts the tunnel is for, so this refuses
 * anything not named -- and the tests that matter most are the ones showing it fails closed.
 */
class AllowedHostsTest {

    @Test
    void nothingConfiguredPermitsEverything() {
        // The default has to be unchanged, or adding the option breaks every existing tunnel.
        for (AllowedHosts hosts : new AllowedHosts[]{
                AllowedHosts.unrestricted(), AllowedHosts.parse(null),
                AllowedHosts.parse(""), AllowedHosts.parse("  ,  ")}) {
            assertThat(hosts.isUnrestricted()).isTrue();
            assertThat(hosts.permits("anything.example.com")).isTrue();
        }
    }

    @Test
    void anExactHostIsPermittedAndNothingElseIs() {
        AllowedHosts hosts = AllowedHosts.parse("staging.example.com");

        assertThat(hosts.permits("staging.example.com")).isTrue();
        assertThat(hosts.permits("evil.example.com")).isFalse();
        assertThat(hosts.permits("example.com")).isFalse();
        // A suffix of a permitted name is not the permitted name.
        assertThat(hosts.permits("notstaging.example.com")).isFalse();
        assertThat(hosts.permits("staging.example.com.evil.test")).isFalse();
    }

    @Test
    void aSubdomainWildcardCoversSubdomains() {
        AllowedHosts hosts = AllowedHosts.parse("*.internal.example");

        assertThat(hosts.permits("api.internal.example")).isTrue();
        assertThat(hosts.permits("deep.nested.internal.example")).isTrue();
    }

    @Test
    void aSubdomainWildcardDoesNotCoverTheApex() {
        // The convention everywhere else, and it keeps a list from quietly granting more than
        // it appears to. Widening to the apex has to be written down.
        AllowedHosts hosts = AllowedHosts.parse("*.internal.example");

        assertThat(hosts.permits("internal.example")).isFalse();
        assertThat(hosts.permits("notinternal.example")).isFalse();
        assertThat(hosts.permits("internal.example.evil.test")).isFalse();
    }

    @Test
    void theApexCanBeAddedAlongsideTheWildcard() {
        AllowedHosts hosts = AllowedHosts.parse("internal.example,*.internal.example");

        assertThat(hosts.permits("internal.example")).isTrue();
        assertThat(hosts.permits("api.internal.example")).isTrue();
    }

    @Test
    void caseAndSpacingDoNotMatter() {
        AllowedHosts hosts = AllowedHosts.parse(" Staging.Example.COM , *.Internal.Example ");

        assertThat(hosts.permits("STAGING.EXAMPLE.COM")).isTrue();
        assertThat(hosts.permits("API.INTERNAL.EXAMPLE")).isTrue();
    }

    @Test
    void anUnknownHostIsRefusedRatherThanPermitted() {
        // A policy that fails open is not a policy.
        AllowedHosts hosts = AllowedHosts.parse("staging.example.com");

        assertThat(hosts.permits(null)).isFalse();
        assertThat(hosts.permits("")).isFalse();
    }

    @Test
    void aBareWildcardIsRefused() {
        // "*" permits everything, which is what omitting the option already does. Accepting it
        // would let a typo silently disable the policy while looking like it was configured.
        assertThatThrownBy(() -> AllowedHosts.parse("*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leave --allow-hosts out entirely");
    }

    @Test
    void wildcardsElsewhereInTheNameAreRefused() {
        // "api.*.example" reads as though it would work; refusing it is better than storing a
        // literal that matches nothing.
        assertThatThrownBy(() -> AllowedHosts.parse("api.*.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading '*.'");
        assertThatThrownBy(() -> AllowedHosts.parse("*.a.*.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void urlsPortsAndLineBreaksAreRefused() {
        assertThatThrownBy(() -> AllowedHosts.parse("https://staging.example.com/"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a URL");
        assertThatThrownBy(() -> AllowedHosts.parse("staging.example.com:443"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not include a port");
        assertThatThrownBy(() -> AllowedHosts.parse("host.example\r\nX: 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spaces or line breaks");
    }

    @Test
    void aHostWithAPortAttachedIsStillMatchedOnItsName() {
        // Callers pass an authority in some paths; the policy is about hosts.
        AllowedHosts hosts = AllowedHosts.parse("staging.example.com");

        assertThat(hosts.permits("staging.example.com:8443")).isTrue();
        assertThat(hosts.permits("evil.example.com:8443")).isFalse();
    }
}
