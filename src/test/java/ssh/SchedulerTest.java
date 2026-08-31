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
        scheduler.scheduleRepeating("repeat", runs::incrementAndGet, 1, 5);
        Thread.sleep(100);

        scheduler.cancel();
        int afterCancel = runs.get();
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
            scheduler.scheduleRepeating("first", first::incrementAndGet, 1, 5);
            Thread.sleep(50);
            scheduler.scheduleOnce("second", second::countDown, 1);
            int afterReplace = first.get();
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);

            assertThat(first.get()).isEqualTo(afterReplace);
        } finally {
            scheduler.cancel();
        }
    }
}
