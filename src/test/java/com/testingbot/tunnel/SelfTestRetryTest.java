package com.testingbot.tunnel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A startup self-test that failed once must be tried again.
 *
 * <p>Readiness is gated on the self-test, and its checks can fail for reasons that pass a moment
 * later -- the hub still refusing connections on a tunnel server that has only just booted. With
 * nothing retrying it, such a tunnel stayed at 503 for the life of a perfectly healthy SSH
 * session: the reconnect path re-runs everything, but only when the connection actually drops.
 */
class SelfTestRetryTest {

    @TempDir
    Path tempDir;

    private App app;

    @BeforeEach
    void setUp() {
        TunnelMetrics.setTunnelUp(false);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.cancelSelfTestRetry();
        }
        TunnelMetrics.setTunnelUp(false);
    }

    /** An App whose self-test fails the first {@code failures} times it is asked. */
    private static class RetryingApp extends App {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failures;
        private volatile boolean forwarding = true;

        RetryingApp(int failures) {
            this.failures = failures;
            this.selfTestRetryIntervalMs = 25;
        }

        @Override
        boolean selfTest() {
            return calls.incrementAndGet() > failures;
        }

        @Override
        boolean tunnelIsForwarding() {
            return forwarding;
        }
    }

    @Test
    void aTransientFailureBecomesReadyOnRetry() throws Exception {
        Path readyFile = tempDir.resolve("tunnel.ready");
        RetryingApp retrying = new RetryingApp(2);
        app = retrying;
        retrying.setReadyFile(readyFile.toString());

        retrying.scheduleSelfTestRetry();

        Await.until("the retried self-test to report the tunnel ready", TunnelMetrics::isTunnelUp);
        assertThat(Files.exists(readyFile))
                .as("--readyfile follows the same gate as /readyz")
                .isTrue();
    }

    @Test
    void retryingStopsOnceItSucceeds() throws Exception {
        RetryingApp retrying = new RetryingApp(1);
        app = retrying;

        retrying.scheduleSelfTestRetry();
        Await.until("the retry to succeed", TunnelMetrics::isTunnelUp);

        int callsAtSuccess = retrying.calls.get();
        // Asserting that nothing further happens has no condition to poll for: several retry
        // intervals have to pass before the absence means anything.
        Thread.sleep(150);
        assertThat(retrying.calls.get())
                .as("the timer is cancelled once the tunnel is ready")
                .isEqualTo(callsAtSuccess);
    }

    @Test
    void aDroppedConnectionIsLeftToTheReconnectMonitor() throws Exception {
        RetryingApp retrying = new RetryingApp(0);
        app = retrying;
        retrying.forwarding = false;

        retrying.scheduleSelfTestRetry();
        Thread.sleep(150);

        assertThat(retrying.calls.get())
                .as("no point testing a tunnel whose SSH session is down; that path rebuilds it")
                .isZero();
        assertThat(TunnelMetrics.isTunnelUp()).isFalse();
    }

    @Test
    void retryingGivesUpRatherThanRunningForever() throws Exception {
        RetryingApp retrying = new RetryingApp(Integer.MAX_VALUE);
        app = retrying;

        retrying.scheduleSelfTestRetry();

        Await.until("the retry loop to stop after its bounded attempts",
                () -> retrying.calls.get() >= App.SELF_TEST_RETRY_ATTEMPTS);
        Thread.sleep(150);
        assertThat(retrying.calls.get()).isEqualTo(App.SELF_TEST_RETRY_ATTEMPTS);
        assertThat(TunnelMetrics.isTunnelUp()).isFalse();
    }
}
