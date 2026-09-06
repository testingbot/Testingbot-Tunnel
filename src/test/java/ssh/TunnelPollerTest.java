package ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testingbot.tunnel.Api;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.TunnelFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The poll that waits for a freshly created tunnel to become READY.
 *
 * <p>Driven through an injected scheduler rather than the real five-second timer. The previous
 * version slept 6 and 16 seconds per test -- about eighty seconds for the class -- and still
 * could not distinguish a poller that cancelled itself from one that never polled at all, since
 * every assertion was {@code never(tunnelReady)}, which holds either way.
 */
class TunnelPollerTest {

    /** Runs the scheduled task on demand and records cancellation. */
    private static final class ManualScheduler implements Scheduler {
        private Runnable task;
        private int cancels;

        @Override
        public void scheduleOnce(String name, Runnable task, long delayMs) {
            this.task = task;
        }

        @Override
        public void scheduleRepeating(String name, Runnable task, long delayMs, long periodMs) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancels++;
        }

        void poll() {
            task.run();
        }

        boolean wasCancelled() {
            return cancels > 0;
        }
    }

    private App app;
    private Api api;
    private ObjectMapper objectMapper;
    private ManualScheduler scheduler;

    @BeforeEach
    void setUp() {
        app = mock(App.class);
        api = mock(Api.class);
        when(app.getApi()).thenReturn(api);
        objectMapper = new ObjectMapper();
        scheduler = new ManualScheduler();
    }

    private TunnelPoller poller() {
        return new TunnelPoller(app, "tunnel123", scheduler);
    }

    private JsonNode state(String value) throws Exception {
        return objectMapper.readTree("{\"state\":\"" + value + "\"}");
    }

    @Test
    void aReadyTunnelIsHandedOverAndThePollStops() throws Exception {
        when(api.pollTunnel(anyString())).thenReturn(state("READY"));
        poller();

        scheduler.poll();

        verify(app).tunnelReady(any());
        assertThat(scheduler.wasCancelled())
                .as("a tunnel that is up must not keep being polled for")
                .isTrue();
    }

    @Test
    void aTunnelStillBootingIsPolledAgain() throws Exception {
        when(api.pollTunnel(anyString())).thenReturn(state("BOOTING"));
        poller();

        scheduler.poll();
        scheduler.poll();

        // The poll happened -- which never(tunnelReady) alone could not show -- and the
        // schedule was left in place.
        verify(api, times(2)).pollTunnel("tunnel123");
        verify(app, never()).tunnelReady(any());
        assertThat(scheduler.wasCancelled()).isFalse();
    }

    @Test
    void oneFailedPollIsRetriedRatherThanEndingTheTunnel() throws Exception {
        when(api.pollTunnel(anyString()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(state("READY"));
        poller();

        scheduler.poll();

        // The previous behaviour, and what the previous test asserted: a single exception
        // cancelled the schedule for good. Nothing then terminated or reported, so the process
        // stayed alive and permanently unready on one transient API error.
        assertThat(scheduler.wasCancelled())
                .as("a single failed poll must not stop the schedule")
                .isFalse();
        verify(app, never()).setupFailed(anyString(), org.mockito.ArgumentMatchers.anyInt());

        scheduler.poll();

        verify(app).tunnelReady(any());
    }

    @Test
    void aSustainedRunOfFailuresGivesUpAndReportsIt() throws Exception {
        when(api.pollTunnel(anyString())).thenThrow(new RuntimeException("boom"));
        poller();

        for (int i = 0; i < TunnelPoller.MAX_CONSECUTIVE_ERRORS; i++) {
            scheduler.poll();
        }

        // Retrying forever would be the opposite mistake. The schedule stops, and -- the part
        // that was missing -- the app is told, so it stops reporting ready and the command line
        // client exits instead of lingering.
        assertThat(scheduler.wasCancelled()).isTrue();
        verify(api, times(TunnelPoller.MAX_CONSECUTIVE_ERRORS)).pollTunnel("tunnel123");
        verify(app).setupFailed(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aSuccessfulPollResetsTheFailureCount() throws Exception {
        when(api.pollTunnel(anyString()))
                .thenThrow(new RuntimeException("boom"))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(state("BOOTING"))
                .thenThrow(new RuntimeException("boom"))
                .thenThrow(new RuntimeException("boom"));
        poller();

        for (int i = 0; i < 5; i++) {
            scheduler.poll();
        }

        // Four failures in total but never MAX_CONSECUTIVE_ERRORS in a row, which is the
        // distinction the counter exists to make: an unreliable network is not a dead tunnel.
        assertThat(scheduler.wasCancelled()).isFalse();
        verify(app, never()).setupFailed(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aTunnelThatCameUpButCouldNotBeSetUpStopsTheSchedule() throws Exception {
        when(api.pollTunnel(anyString())).thenReturn(state("READY"));
        org.mockito.Mockito.doThrow(new TunnelFailedException("no port"))
                .when(app).tunnelReady(any());
        poller();

        scheduler.poll();

        // Runs on a timer thread with nobody to propagate to, so it must stop itself rather
        // than retry a setup that already failed -- and say so, rather than leaving a live
        // process that will never be ready.
        assertThat(scheduler.wasCancelled()).isTrue();
        verify(app).setupFailed(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cancelStopsTheSchedule() throws Exception {
        when(api.pollTunnel(anyString())).thenReturn(state("BOOTING"));
        TunnelPoller poller = poller();

        poller.cancel();

        assertThat(scheduler.wasCancelled()).isTrue();
    }
}
