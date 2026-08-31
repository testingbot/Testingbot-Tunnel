package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import ssh.Scheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pid file an external supervisor deletes to stop the tunnel.
 *
 * <p>Previously untestable: the only observable behaviour was {@code System.exit(0)} on a timer.
 * A wrong file name here means a supervisor silently cannot stop the tunnel, which is the sort
 * of fault that only shows up in someone's CI at an inconvenient moment.
 */
class PidPollerTest {

    /** Captures the scheduled poll so the test can run it on demand. */
    private static final class ManualScheduler implements Scheduler {
        private Runnable pending;
        private int cancels;

        @Override
        public void scheduleOnce(String name, Runnable task, long delayMs) {
            pending = task;
        }

        @Override
        public void scheduleRepeating(String name, Runnable task, long delayMs, long periodMs) {
            pending = task;
        }

        @Override
        public void cancel() {
            cancels++;
        }

        void poll() {
            pending.run();
        }
    }

    @Test
    void fileNameIsPerTunnelWhenAnIdentifierIsSet() {
        // Two tunnels on one machine must not share a pid file: deleting it would stop
        // whichever noticed first.
        assertThat(PidPoller.pidFileName(null)).isEqualTo("testingbot-tunnel.pid");
        assertThat(PidPoller.pidFileName("")).isEqualTo("testingbot-tunnel.pid");
        assertThat(PidPoller.pidFileName("ci-42")).isEqualTo("testingbot-tunnel-ci-42.pid");
    }

    @Test
    void theFileIsCreatedWithAnExplanationOfWhatItDoes(@TempDir Path tmp) throws Exception {
        File pid = tmp.resolve("tunnel.pid").toFile();

        PidPoller poller = new PidPoller(pid, new ManualScheduler(), () -> { }, false);

        assertThat(pid).exists();
        assertThat(Files.readString(pid.toPath())).contains("Remove this file to shutdown");
        assertThat(poller.isStarted()).isTrue();
    }

    @Test
    void anExistingFileIsLeftAlone(@TempDir Path tmp) throws Exception {
        File pid = tmp.resolve("tunnel.pid").toFile();
        Files.writeString(pid.toPath(), "written by someone else");

        new PidPoller(pid, new ManualScheduler(), () -> { }, false);

        assertThat(Files.readString(pid.toPath())).isEqualTo("written by someone else");
    }

    @Test
    void pollingWhileTheFileExistsDoesNothing(@TempDir Path tmp) {
        File pid = tmp.resolve("tunnel.pid").toFile();
        AtomicInteger shutdowns = new AtomicInteger();
        ManualScheduler scheduler = new ManualScheduler();
        new PidPoller(pid, scheduler, shutdowns::incrementAndGet, false);

        scheduler.poll();
        scheduler.poll();

        assertThat(shutdowns).hasValue(0);
    }

    @Test
    void removingTheFileTriggersShutdownAndStopsPolling(@TempDir Path tmp) {
        File pid = tmp.resolve("tunnel.pid").toFile();
        AtomicInteger shutdowns = new AtomicInteger();
        ManualScheduler scheduler = new ManualScheduler();
        new PidPoller(pid, scheduler, shutdowns::incrementAndGet, false);

        assertThat(pid.delete()).isTrue();
        scheduler.poll();

        assertThat(shutdowns).hasValue(1);
        assertThat(scheduler.cancels).isPositive();
    }

    @Test
    void anUncreatableFileDoesNotStartPolling(@TempDir Path tmp) {
        // A path under a directory that does not exist: creation fails, and the tunnel must
        // carry on rather than dying over a supervisor convenience.
        File unwritable = tmp.resolve("no-such-directory").resolve("tunnel.pid").toFile();

        PidPoller poller = new PidPoller(unwritable, new ManualScheduler(), () -> { }, false);

        assertThat(poller.isStarted()).isFalse();
        assertThat(unwritable).doesNotExist();
    }

    @Test
    void cancelStopsTheScheduler(@TempDir Path tmp) {
        ManualScheduler scheduler = new ManualScheduler();
        PidPoller poller = new PidPoller(tmp.resolve("tunnel.pid").toFile(), scheduler,
                () -> { }, false);

        poller.cancel();

        assertThat(scheduler.cancels).isPositive();
    }
}
