package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code --krb5-hosts} allowlist.
 *
 * <p>Validation is the substance here. This is the one list whose entries decide who receives a
 * Kerberos service ticket, so an entry that cannot match -- a URL, a host with a port, a
 * wildcard someone expected to expand -- has to be refused rather than silently matching
 * nothing, which would look exactly like the feature being off.
 */
class NegotiateHostsTest {

    @Test
    void hostsAreTrimmedLowercasedAndDeduplicated() {
        NegotiateHosts hosts = NegotiateHosts.parse(" Intranet.Example.COM , wiki.internal , intranet.example.com ");

        assertThat(hosts.hosts()).containsExactly("intranet.example.com", "wiki.internal");
        assertThat(hosts.includes("INTRANET.EXAMPLE.COM")).isTrue();
        assertThat(hosts.includes("wiki.internal")).isTrue();
    }

    @Test
    void nothingConfiguredMatchesNothing() {
        assertThat(NegotiateHosts.none().isEmpty()).isTrue();
        assertThat(NegotiateHosts.parse(null).includes("anything.example")).isFalse();
        assertThat(NegotiateHosts.parse("").isEmpty()).isTrue();
        assertThat(NegotiateHosts.parse("  ,  ").isEmpty()).isTrue();
    }

    @Test
    void anUnlistedHostIsNeverIncluded() {
        NegotiateHosts hosts = NegotiateHosts.parse("intranet.example.com");

        assertThat(hosts.includes("evil.example.com")).isFalse();
        assertThat(hosts.includes("intranet.example.com.evil.example")).isFalse();
        // Not a suffix match: a list entry is the whole host or nothing.
        assertThat(hosts.includes("sub.intranet.example.com")).isFalse();
        assertThat(hosts.includes(null)).isFalse();
    }

    @Test
    void wildcardsAreRefusedRatherThanTakenLiterally() {
        // Someone writing *.internal expects expansion. Storing it as a literal host name that
        // never matches would look identical to the feature being switched off.
        assertThatThrownBy(() -> NegotiateHosts.parse("*.internal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcards are not accepted");
    }

    @Test
    void urlsAndPortsAreRefused() {
        assertThatThrownBy(() -> NegotiateHosts.parse("https://intranet.example.com/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a URL");
        assertThatThrownBy(() -> NegotiateHosts.parse("intranet.example.com:443"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not include a port");
    }

    @Test
    void lineBreaksAreRefused() {
        assertThatThrownBy(() -> NegotiateHosts.parse("host.example\r\nX-Injected: 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spaces or line breaks");
    }
}
