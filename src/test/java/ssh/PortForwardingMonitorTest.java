package ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two decisions the port-forwarding monitor makes every 15 seconds.
 *
 * <p>Both were previously buried in a TimerTask that needed a live SSH session to reach, so
 * neither was covered -- including a substring match on a port number that is looser than it
 * looks.
 */
class PortForwardingMonitorTest {

    @Test
    void localForwardingIsActiveWhenTheSshPortAppears() {
        String[] forwards = {"4446:hub.testingbot.com:80"};

        assertThat(SSHTunnel.localForwardingActive(forwards, 4446)).isTrue();
        assertThat(SSHTunnel.localForwardingActive(forwards, 9999)).isFalse();
    }

    @Test
    void anEmptyOrAbsentListMeansNotActive() {
        // JSch returns null when there is no session, which used to be handled inline.
        assertThat(SSHTunnel.localForwardingActive(null, 4446)).isFalse();
        assertThat(SSHTunnel.localForwardingActive(new String[0], 4446)).isFalse();
        assertThat(SSHTunnel.localForwardingActive(new String[]{null}, 4446)).isFalse();
    }

    @Test
    void anyEntryInTheListCounts() {
        String[] forwards = {"1234:other:80", "4446:hub.testingbot.com:80"};

        assertThat(SSHTunnel.localForwardingActive(forwards, 4446)).isTrue();
    }

    @Test
    void aPortThatMerelySharesAPrefixIsNotAMatch() {
        // Was documented as harmless: "445" is a substring of "4456", so the old contains()
        // check reported a forward that does not exist. It is not harmless -- this answers "is
        // my forward still there", and the monitor repairs it when the answer is no. A false
        // positive means the repair never runs and every request through the tunnel keeps
        // failing, with the log insisting forwarding is fine.
        assertThat(SSHTunnel.localForwardingActive(new String[]{"4456:h:80"}, 445)).isFalse();
        assertThat(SSHTunnel.localForwardingActive(new String[]{"4456:h:80"}, 4456)).isTrue();
    }

    @Test
    void onlyTheLocalPortIsCompared() {
        // The digits also appear in the destination host and the remote port, and neither
        // identifies this forward.
        assertThat(SSHTunnel.localForwardingActive(new String[]{"9999:host80.example:80"}, 80))
                .as("the remote port is not the local port")
                .isFalse();
        assertThat(SSHTunnel.localForwardingActive(new String[]{"9999:h4446.example:80"}, 4446))
                .as("digits in the destination host are not a port")
                .isFalse();
    }

    @Test
    void aBindAddressBeforeThePortIsUnderstood() {
        // JSch renders a bound forward as "127.0.0.1:4446:host:80".
        assertThat(SSHTunnel.localForwardingActive(new String[]{"127.0.0.1:4446:h:80"}, 4446))
                .isTrue();
        assertThat(SSHTunnel.localForwardingActive(new String[]{"127.0.0.1:4446:h:80"}, 127))
                .as("the bind address is not the port")
                .isFalse();
    }

    @Test
    void malformedEntriesDoNotMatch() {
        assertThat(SSHTunnel.localForwardingActive(new String[]{"nonsense"}, 4446)).isFalse();
        assertThat(SSHTunnel.localForwardingActive(new String[]{""}, 4446)).isFalse();
        assertThat(SSHTunnel.localForwardingActive(new String[]{null}, 4446)).isFalse();
        assertThat(SSHTunnel.localForwardingActive(null, 4446)).isFalse();
    }

    @Test
    void reverseHealthReportsOnlyTheTransitions() {
        // Reported every poll, a broken reverse forward would bury the rest of the log.
        SSHTunnel.ReverseHealthTracker tracker = new SSHTunnel.ReverseHealthTracker();

        assertThat(tracker.update(true)).isEqualTo(SSHTunnel.ReverseHealthTracker.Change.UNCHANGED);
        assertThat(tracker.update(false)).isEqualTo(SSHTunnel.ReverseHealthTracker.Change.BROKEN);
        assertThat(tracker.update(false)).isEqualTo(SSHTunnel.ReverseHealthTracker.Change.UNCHANGED);
        assertThat(tracker.update(true)).isEqualTo(SSHTunnel.ReverseHealthTracker.Change.RESTORED);
        assertThat(tracker.update(true)).isEqualTo(SSHTunnel.ReverseHealthTracker.Change.UNCHANGED);
    }

    @Test
    void reverseHealthStartsOptimistic() {
        // Otherwise the first poll of a healthy tunnel would announce a recovery that never
        // happened.
        SSHTunnel.ReverseHealthTracker tracker = new SSHTunnel.ReverseHealthTracker();

        assertThat(tracker.isHealthy()).isTrue();
        assertThat(tracker.update(true)).isEqualTo(SSHTunnel.ReverseHealthTracker.Change.UNCHANGED);
    }

    @Test
    void reverseHealthTracksTheCurrentState() {
        SSHTunnel.ReverseHealthTracker tracker = new SSHTunnel.ReverseHealthTracker();

        tracker.update(false);
        assertThat(tracker.isHealthy()).isFalse();
        tracker.update(true);
        assertThat(tracker.isHealthy()).isTrue();
    }
}
