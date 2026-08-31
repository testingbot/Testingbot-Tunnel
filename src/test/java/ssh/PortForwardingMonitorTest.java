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
    void theMatchIsASubstringSoADigitPrefixAlsoMatches() {
        // Documenting the real behaviour rather than an assumed one: "445" is a substring of
        // "4456", so a port that merely shares a prefix reads as active. Harmless in practice --
        // the alternative is a false "forwarding lost" and a needless restart -- but it is not
        // what the code appears to say at a glance.
        assertThat(SSHTunnel.localForwardingActive(new String[]{"4456:h:80"}, 445)).isTrue();
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
