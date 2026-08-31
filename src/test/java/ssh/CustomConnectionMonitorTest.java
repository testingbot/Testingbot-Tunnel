package ssh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconnect behaviour after the SSH connection drops.
 *
 * <p>This only runs when something has already gone wrong on a customer's network, so it is both
 * the hardest code to observe in the wild and the most costly to get wrong. Driven here through
 * stand-ins for the tunnel, the host and the scheduler, so the retry, give-up and recovery paths
 * are exercised without an SSH server and without waiting out the real five-second delays.
 */
class CustomConnectionMonitorTest {

    /** Records what was scheduled and lets the test decide when it runs. */
    private static final class ManualScheduler implements Scheduler {
        private Runnable pending;
        private int scheduled;
        private int cancels;

        @Override
        public void scheduleOnce(String name, Runnable task, long delayMs) {
            pending = task;
            scheduled++;
        }

        @Override
        public void scheduleRepeating(String name, Runnable task, long delayMs, long periodMs) {
            pending = task;
            scheduled++;
        }

        @Override
        public void cancel() {
            cancels++;
            pending = null;
        }

        /** Runs whatever is pending, as the Timer would have. */
        void fire() {
            Runnable task = pending;
            pending = null;
            if (task == null) {
                throw new IllegalStateException("nothing was scheduled");
            }
            task.run();
        }

        boolean hasPending() {
            return pending != null;
        }
    }

    private static final class FakeTunnel implements ReconnectableTunnel {
        private final List<String> calls = new ArrayList<>();
        private boolean shuttingDown;
        private boolean authenticated;
        private RuntimeException connectFailure;

        @Override
        public String getConnectionId() {
            return "test-conn";
        }

        @Override
        public boolean isShuttingDown() {
            return shuttingDown;
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public void connect() {
            calls.add("connect");
            if (connectFailure != null) {
                throw connectFailure;
            }
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public void createPortForwarding() {
            calls.add("createPortForwarding");
        }
    }

    private static final class FakeHost implements ReconnectHost {
        private final List<String> calls = new ArrayList<>();
        private Exception rebuildFailure;

        @Override
        public void stopLocalProxy() {
            calls.add("stopLocalProxy");
        }

        @Override
        public void startLocalProxy() {
            calls.add("startLocalProxy");
        }

        @Override
        public void rebuildTunnel() throws Exception {
            calls.add("rebuildTunnel");
            if (rebuildFailure != null) {
                throw rebuildFailure;
            }
        }
    }

    private FakeTunnel tunnel;
    private FakeHost host;
    private ManualScheduler scheduler;
    private CustomConnectionMonitor monitor;

    @BeforeEach
    void setUp() {
        tunnel = new FakeTunnel();
        host = new FakeHost();
        scheduler = new ManualScheduler();
        monitor = new CustomConnectionMonitor(tunnel, host, scheduler, 5000);
    }

    @Test
    void aDropStopsTheLocalProxyAndSchedulesARetry() {
        monitor.connectionLost(new RuntimeException("connection reset"));

        assertThat(host.calls).containsExactly("stopLocalProxy");
        assertThat(scheduler.scheduled).isEqualTo(1);
        assertThat(monitor.isRetrying()).isTrue();
    }

    @Test
    void aDropDuringShutdownIsIgnored() {
        // Tearing the tunnel down deliberately drops the connection; reconnecting would fight
        // the shutdown and could leave a tunnel registered server-side.
        tunnel.shuttingDown = true;

        monitor.connectionLost(new RuntimeException("expected"));

        assertThat(host.calls).isEmpty();
        assertThat(scheduler.scheduled).isZero();
        assertThat(monitor.isRetrying()).isFalse();
    }

    @Test
    void furtherDropsWhileRetryingDoNotStackUpTimers() {
        // Several channels can report the same failure; each starting its own retry cycle would
        // have them racing to reconnect the same tunnel.
        monitor.connectionLost(new RuntimeException("first"));
        monitor.connectionLost(new RuntimeException("second"));
        monitor.connectionLost(new RuntimeException("third"));

        assertThat(scheduler.scheduled).isEqualTo(1);
    }

    @Test
    void aNullReasonDoesNotBreakTheHandler() {
        // JSch has handed us a null cause before; losing the reconnect over a log message
        // would turn a recoverable drop into a dead tunnel.
        monitor.connectionLost(null);

        assertThat(scheduler.scheduled).isEqualTo(1);
    }

    @Test
    void aSuccessfulReconnectRestoresTheProxyThenTheForwarding() {
        tunnel.authenticated = true;
        monitor.connectionLost(new RuntimeException("drop"));

        scheduler.fire();

        // Order matters: the proxy must be listening before the reverse forward is re-established.
        assertThat(tunnel.calls).containsExactly("stop", "connect", "createPortForwarding");
        assertThat(host.calls).containsExactly("stopLocalProxy", "startLocalProxy");
        assertThat(monitor.isRetrying()).isFalse();
        assertThat(monitor.getRetryAttempts()).isZero();
        assertThat(scheduler.hasPending()).isFalse();
    }

    @Test
    void anUnauthenticatedReconnectIsRetried() {
        tunnel.authenticated = false;
        monitor.connectionLost(new RuntimeException("drop"));

        scheduler.fire();

        assertThat(monitor.getRetryAttempts()).isEqualTo(1);
        assertThat(scheduler.hasPending()).as("another attempt should be queued").isTrue();
        assertThat(host.calls).doesNotContain("startLocalProxy");
    }

    @Test
    void aThrowingConnectIsRetriedRatherThanEscaping() {
        // A TimerTask that throws is never rescheduled, so an exception escaping here would
        // silently end all recovery.
        tunnel.connectFailure = new RuntimeException("network unreachable");
        monitor.connectionLost(new RuntimeException("drop"));

        scheduler.fire();

        assertThat(monitor.getRetryAttempts()).isEqualTo(1);
        assertThat(scheduler.hasPending()).isTrue();
    }

    @Test
    void attemptsAreCountedUpToTheLimit() {
        monitor.connectionLost(new RuntimeException("drop"));

        for (int i = 1; i < CustomConnectionMonitor.MAX_RETRIES; i++) {
            scheduler.fire();
            assertThat(monitor.getRetryAttempts()).isEqualTo(i);
            assertThat(host.calls).doesNotContain("rebuildTunnel");
        }
    }

    @Test
    void theTunnelIsRebuiltOnceTheRetryLimitIsReached() {
        monitor.connectionLost(new RuntimeException("drop"));

        for (int i = 0; i < CustomConnectionMonitor.MAX_RETRIES; i++) {
            scheduler.fire();
        }

        assertThat(host.calls).contains("rebuildTunnel");
        // Reset, so a later drop gets a fresh cycle rather than giving up immediately.
        assertThat(monitor.getRetryAttempts()).isZero();
        assertThat(monitor.isRetrying()).isFalse();
        assertThat(scheduler.hasPending()).isFalse();
    }

    @Test
    void aFailedRebuildDoesNotLeaveTheMonitorWedged() {
        // If rebuilding throws, the monitor must still be able to start a new cycle later.
        host.rebuildFailure = new IllegalStateException("API unreachable");
        monitor.connectionLost(new RuntimeException("drop"));
        for (int i = 0; i < CustomConnectionMonitor.MAX_RETRIES; i++) {
            scheduler.fire();
        }

        assertThat(monitor.isRetrying()).isFalse();

        monitor.connectionLost(new RuntimeException("another drop"));
        assertThat(scheduler.hasPending()).isTrue();
    }

    @Test
    void recoveryResetsTheCounterForTheNextOutage() {
        monitor.connectionLost(new RuntimeException("drop"));
        scheduler.fire();
        scheduler.fire();
        assertThat(monitor.getRetryAttempts()).isEqualTo(2);

        tunnel.authenticated = true;
        scheduler.fire();
        assertThat(monitor.getRetryAttempts()).isZero();

        // A second outage starts from one, not from three.
        tunnel.authenticated = false;
        monitor.connectionLost(new RuntimeException("later drop"));
        scheduler.fire();
        assertThat(monitor.getRetryAttempts()).isEqualTo(1);
    }
}
