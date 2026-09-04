package ssh;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The Timer-backed scheduler the tunnel actually runs on. */
class SchedulerTest {

    @Test
    void runsAOneShotTask() throws Exception {
        Scheduler scheduler = Scheduler.timerBased();
        CountDownLatch ran = new CountDownLatch(1);
        try {
            scheduler.scheduleOnce("once", ran::countDown, 1);

            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.cancel();
        }
    }

    @Test
    void repeatsUntilCancelled() throws Exception {
        Scheduler scheduler = Scheduler.timerBased();
        CountDownLatch threeRuns = new CountDownLatch(3);
        try {
            scheduler.scheduleRepeating("repeat", threeRuns::countDown, 1, 5);

            assertThat(threeRuns.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.cancel();
        }
    }

    @Test
    void cancelStopsFurtherRuns() throws Exception {
        Scheduler scheduler = Scheduler.timerBased();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        scheduler.scheduleRepeating("repeat", () -> {
            runs.incrementAndGet();
            started.countDown();
        }, 1, 5);

        // Wait for the task to have actually run, rather than sleeping and hoping. Without
        // this the test passed against a scheduleRepeating that did nothing: 0 stays 0 across
        // a cancel just as convincingly as a real count does.
        assertThat(started.await(5, TimeUnit.SECONDS))
                .as("the repeating task must run before cancelling proves anything")
                .isTrue();

        scheduler.cancel();
        int afterCancel = runs.get();
        assertThat(afterCancel).isPositive();
        Thread.sleep(100);

        assertThat(runs.get()).isEqualTo(afterCancel);
    }

    @Test
    void aThrowingTaskDoesNotKillTheScheduler() throws Exception {
        // An uncaught throwable from a TimerTask terminates the Timer thread and cancels
        // everything on it. On the reconnect path that is terminal: the retry flag stays set, so
        // every later connection-lost report is dropped and the tunnel never comes back.
        Scheduler scheduler = Scheduler.timerBased();
        CountDownLatch ranAgain = new CountDownLatch(2);
        try {
            scheduler.scheduleRepeating("boom", () -> {
                ranAgain.countDown();
                throw new IllegalStateException("task blew up");
            }, 1, 5);

            assertThat(ranAgain.await(5, TimeUnit.SECONDS))
                    .as("the scheduler should keep running after a task throws")
                    .isTrue();
        } finally {
            scheduler.cancel();
        }
    }

    @Test
    void anErrorIsAbsorbedTheSameWay() throws Exception {
        // The throwables that actually kill a Timer are the ones that are not Exceptions.
        Scheduler scheduler = Scheduler.timerBased();
        CountDownLatch ranAgain = new CountDownLatch(2);
        try {
            scheduler.scheduleRepeating("boom", () -> {
                ranAgain.countDown();
                throw new StackOverflowError("simulated");
            }, 1, 5);

            assertThat(ranAgain.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.cancel();
        }
    }

    @Test
    void cancelIsSafeWhenNothingWasScheduledAndWhenRepeated() {
        Scheduler scheduler = Scheduler.timerBased();

        scheduler.cancel();
        scheduler.cancel();

        assertThat(scheduler).isNotNull();
    }

    @Test
    void schedulingAgainReplacesTheOutstandingTask() throws Exception {
        // The reconnect path schedules a new attempt each time; the previous timer must go, or
        // attempts would pile up.
        Scheduler scheduler = Scheduler.timerBased();
        AtomicInteger first = new AtomicInteger();
        CountDownLatch second = new CountDownLatch(1);
        try {
            CountDownLatch firstRan = new CountDownLatch(1);
            scheduler.scheduleRepeating("first", () -> {
                first.incrementAndGet();
                firstRan.countDown();
            }, 1, 5);
            // Same reasoning as cancelStopsFurtherRuns: the first task must be established
            // before replacing it can be shown to have stopped it.
            assertThat(firstRan.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.scheduleOnce("second", second::countDown, 1);
            int afterReplace = first.get();
            assertThat(afterReplace).isPositive();
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);

            assertThat(first.get()).isEqualTo(afterReplace);
        } finally {
            scheduler.cancel();
        }
    }
}
