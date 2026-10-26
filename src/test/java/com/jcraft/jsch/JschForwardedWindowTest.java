package com.jcraft.jsch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the vendored ChannelForwardedTCPIP (src/main/java/com/jcraft/jsch): it must be the class
 * that is loaded, with the larger window, and it must come from the JSch version it was copied from.
 */
class JschForwardedWindowTest {

    /** The JSch release ChannelForwardedTCPIP.java was copied from. */
    private static final String VENDORED_FROM = "2.28.8";

    @Test
    void theVendoredClassIsTheOneLoaded() {
        ChannelForwardedTCPIP channel = new ChannelForwardedTCPIP();

        assertThat(channel.getLocalWindowSizeMax()).isEqualTo(0x200000);
        assertThat(channel.lmpsize).isEqualTo(0x8000);
    }

    @Test
    void jschIsStillTheVersionItWasCopiedFrom() {
        // A failure here is a JSch upgrade: copy the new version's ChannelForwardedTCPIP over the
        // vendored one, re-apply the two constants, and update VENDORED_FROM.
        assertThat(JSch.VERSION).isEqualTo(VENDORED_FROM);
    }
}
