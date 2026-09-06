package com.testingbot.tunnel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Waits for a condition instead of sleeping for a guess.
 *
 * <p>Tests here used {@code Thread.sleep(500)} before asserting on something another thread had
 * to produce -- a server finishing its bind, a log record being written, a statistic being
 * updated. That is wrong in both directions at once. It is too long whenever the thing is
 * already done, which it almost always is: {@code InsightServerTest} spent 5.5 of its 6.3
 * seconds asleep. And it is too short whenever the machine is loaded, which is exactly when CI
 * runs, so the failure arrives as an assertion about logging or statistics that says nothing
 * about timing.
 *
 * <p>Polling fixes both: it returns as soon as the condition holds, and it can afford a timeout
 * long enough to cover a slow runner because that cost is only paid on a genuine failure.
 *
 * <p>This does not suit every wait. Asserting that something did <em>not</em> happen has to give
 * it time to happen first, and there is no condition to poll for -- see {@code ProxyLoopTest},
 * which keeps its sleep and says why.
 */
public final class Await {

    /** Long enough for a loaded CI runner; only ever paid in full when the test is failing. */
    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    private static final long POLL_INTERVAL_MS = 20;

    private Await() {
    }

    /**
     * @param what        described in the failure message, so a timeout says what never happened
     * @param condition   polled until true
     * @throws AssertionError if it is still false when the timeout expires
     */
    public static void until(String what, BooleanSupplier condition) {
        until(what, DEFAULT_TIMEOUT_MS, condition);
    }

    public static void until(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AssertionError lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                lastFailure = null;
            } catch (AssertionError notYet) {
                // A condition expressed as an assertion is allowed to fail while we wait; only
                // the last one matters, and only if we run out of time.
                lastFailure = notYet;
            }
            sleep();
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Timed out after " + timeoutMs + "ms waiting for: " + what);
    }

    /**
     * As {@link #until}, for a condition that produces a value.
     *
     * @return the first non-null, non-empty value the supplier returns
     */
    public static <T> T value(String what, Supplier<T> supplier) {
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            T candidate = supplier.get();
            if (candidate != null
                    && !(candidate instanceof CharSequence text && text.length() == 0)
                    && !(candidate instanceof java.util.Collection<?> c && c.isEmpty())) {
                return candidate;
            }
            sleep();
        }
        throw new AssertionError(
                "Timed out after " + DEFAULT_TIMEOUT_MS + "ms waiting for: " + what);
    }

    /**
     * Polls for up to {@code timeoutMs} and reports whether the condition held, without failing.
     *
     * <p>For a helper shared by tests that expect something and tests that expect nothing: the
     * first kind returns as soon as it appears, the second pays the full wait it needs, and
     * neither has to know which it is.
     *
     * @return true if the condition became true within the timeout
     */
    public static boolean atMost(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep();
        }
        return condition.getAsBoolean();
    }

    /** Waits until something accepts connections on {@code port}. */
    public static void serverOn(int port) {
        until("a server listening on port " + port, () -> {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
                return true;
            } catch (IOException notYet) {
                return false;
            }
        });
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", interrupted);
        }
    }
}
