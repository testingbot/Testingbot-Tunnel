package com.testingbot.tunnel;

import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A dial must be counted once, whatever the dial path does.
 *
 * <p>The three hand-rolled dial paths completed their promise from inside the same try whose
 * catch also completes it. Jetty runs the rest of its wiring synchronously inside
 * {@code succeeded()}, and that can throw -- {@code SelectorManager.accept()} raises NPE once the
 * manager has been stopped, which every SSH reconnect does. The promise was then completed a
 * second time, so one dial was recorded as both a success and a failure and the duration observed
 * twice.
 *
 * <p>The paths themselves now complete outside the try; this guard is the belt-and-braces that
 * keeps the metric honest regardless.
 */
class DialCompletionTest {

    @Test
    void aSecondCompletionDoesNotCountTwice() {
        AtomicInteger delegateSucceeded = new AtomicInteger();
        AtomicInteger delegateFailed = new AtomicInteger();
        Promise<String> delegate = new Promise<>() {
            @Override
            public void succeeded(String result) {
                delegateSucceeded.incrementAndGet();
            }

            @Override
            public void failed(Throwable x) {
                delegateFailed.incrementAndGet();
            }
        };

        double successesBefore = TunnelMetrics.DIAL_TOTAL.labels("connect", "success").get();
        double failuresBefore = TunnelMetrics.DIAL_TOTAL.labels("connect", "failure").get();

        Promise<String> timed = TunnelMetrics.timedDial("connect", delegate);
        timed.succeeded("channel");
        timed.failed(new IllegalStateException("wiring blew up after hand-off"));

        assertThat(TunnelMetrics.DIAL_TOTAL.labels("connect", "success").get() - successesBefore)
                .as("exactly one success recorded").isEqualTo(1.0);
        assertThat(TunnelMetrics.DIAL_TOTAL.labels("connect", "failure").get() - failuresBefore)
                .as("the second completion must not also count as a failure").isZero();

        // The delegate still sees both calls: only the accounting is deduplicated, so Jetty's
        // own state machine is unaffected.
        assertThat(delegateSucceeded).hasValue(1);
        assertThat(delegateFailed).hasValue(1);
    }

    @Test
    void aFailureFollowedByASuccessIsAlsoCountedOnce() {
        double failuresBefore = TunnelMetrics.DIAL_TOTAL.labels("websocket", "failure").get();
        double successesBefore = TunnelMetrics.DIAL_TOTAL.labels("websocket", "success").get();

        Promise<String> timed = TunnelMetrics.timedDial("websocket", new Promise<>() {
            @Override
            public void succeeded(String result) {
            }

            @Override
            public void failed(Throwable x) {
            }
        });
        timed.failed(new IllegalStateException("dial failed"));
        timed.succeeded("channel");

        assertThat(TunnelMetrics.DIAL_TOTAL.labels("websocket", "failure").get() - failuresBefore)
                .isEqualTo(1.0);
        assertThat(TunnelMetrics.DIAL_TOTAL.labels("websocket", "success").get() - successesBefore)
                .isZero();
    }

    @Test
    void anExceptionFromTheDelegateStillPropagates() {
        // The guard must not swallow a real failure from Jetty's wiring.
        Promise<String> timed = TunnelMetrics.timedDial("proxy", new Promise<>() {
            @Override
            public void succeeded(String result) {
                throw new IllegalStateException("accept() after stop");
            }

            @Override
            public void failed(Throwable x) {
            }
        });

        assertThatThrownBy(() -> timed.succeeded("channel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accept() after stop");
    }
}
