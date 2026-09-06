package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status-line parser shared by the CONNECT and WebSocket relays.
 *
 * <p>The WebSocket relay used to ask whether the first line {@code contains("101")}. The cases
 * that gets wrong are not hypothetical -- a target that refuses an upgrade answers with a status
 * and a reason phrase, and the phrase is written by whoever wrote the target.
 */
class HttpStatusLineTest {

    @Test
    void aRealSwitchingProtocolsIsAccepted() {
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.1 101 Switching Protocols")).isTrue();
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.0 101 Switching Protocols")).isTrue();
        // No reason phrase is legal, and some servers send none.
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.1 101")).isTrue();
    }

    @Test
    void aLongerCodeContaining101IsNot() {
        // contains("101") accepted this, and the relay then spliced the client to a target that
        // had not agreed to a WebSocket at all.
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.1 2101 Nonsense")).isFalse();
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.1 1010 Nonsense")).isFalse();
    }

    @Test
    void aReasonPhraseMentioning101IsNot() {
        // The realistic version of the bug: the failure message itself carries the digits.
        assertThat(HttpStatusLine.isSwitchingProtocols(
                "HTTP/1.1 500 Error 101 in upstream handler")).isFalse();
        assertThat(HttpStatusLine.isSwitchingProtocols(
                "HTTP/1.1 404 Not Found: /socket101")).isFalse();
        assertThat(HttpStatusLine.isSwitchingProtocols(
                "HTTP/1.1 403 Forbidden by rule 101")).isFalse();
    }

    @Test
    void otherSuccessCodesAreNotAnUpgrade() {
        // A 200 to an upgrade request means the server ignored the Upgrade header and answered
        // the GET normally. Relaying that as a WebSocket produces a connection that hangs.
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.1 200 OK")).isFalse();
        assertThat(HttpStatusLine.isSwitchingProtocols("HTTP/1.1 204 No Content")).isFalse();
    }

    @Test
    void rubbishIsRejectedRatherThanGuessedAt() {
        assertThat(HttpStatusLine.parse(null)).isEqualTo(HttpStatusLine.INVALID);
        assertThat(HttpStatusLine.parse("")).isEqualTo(HttpStatusLine.INVALID);
        assertThat(HttpStatusLine.parse("101")).isEqualTo(HttpStatusLine.INVALID);
        assertThat(HttpStatusLine.parse("HTTP/1.1")).isEqualTo(HttpStatusLine.INVALID);
        assertThat(HttpStatusLine.parse("HTTP/1.1 abc")).isEqualTo(HttpStatusLine.INVALID);
        assertThat(HttpStatusLine.parse("GARBAGE 101 x")).isEqualTo(HttpStatusLine.INVALID);
        // Integer.parseInt would take these; a status is exactly three digits.
        assertThat(HttpStatusLine.parse("HTTP/1.1 +101")).isEqualTo(HttpStatusLine.INVALID);
        assertThat(HttpStatusLine.parse("HTTP/1.1 -101")).isEqualTo(HttpStatusLine.INVALID);
    }

    @Test
    void theConnectSideKeepsItsExistingBehaviour() {
        // CustomConnectHandler delegates here now, so its contract is asserted against the
        // shared parser: 2xx and nothing else.
        assertThat(HttpStatusLine.isSuccessfulConnect("HTTP/1.1 200 Connection established")).isTrue();
        assertThat(HttpStatusLine.isSuccessfulConnect("HTTP/1.1 299 Odd but successful")).isTrue();
        assertThat(HttpStatusLine.isSuccessfulConnect("HTTP/1.1 407 Proxy Authentication Required")).isFalse();
        assertThat(HttpStatusLine.isSuccessfulConnect("HTTP/1.1 502 Bad Gateway")).isFalse();
        assertThat(HttpStatusLine.isSuccessfulConnect("HTTP/1.1 101 Switching Protocols")).isFalse();
        assertThat(HttpStatusLine.isSuccessfulConnect(null)).isFalse();
    }

    @Test
    void theTwoEntryPointsAgreeWithTheParser() {
        // Guards the delegation itself: CustomConnectHandler.isSuccessfulConnect is the name the
        // framing tests use, and it must stay the same predicate.
        for (String line : new String[]{
                "HTTP/1.1 200 OK", "HTTP/1.1 101 Switching Protocols",
                "HTTP/1.1 2101 Nonsense", "HTTP/1.1 500 Error 101", null}) {
            assertThat(CustomConnectHandler.isSuccessfulConnect(line))
                    .as("status line: %s", line)
                    .isEqualTo(HttpStatusLine.isSuccessfulConnect(line));
        }
    }
}
