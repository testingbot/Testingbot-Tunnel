package ssh;

import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.TimerTask;

/**
 * Delayed and repeating work, behind an interface so it can be driven directly in tests.
 *
 * <p>The reconnect and monitoring logic is scheduled seconds or tens of seconds apart. Testing it
 * through {@link Timer} would mean either sleeping for real -- turning a unit test into a
 * minute-long one -- or not testing it, which is what happened: the retry, give-up and recovery
 * paths had no coverage at all despite being what the tunnel does when a customer's network
 * hiccups.
 */
public interface Scheduler {

    /** Runs {@code task} once after {@code delayMs}. */
    void scheduleOnce(String name, Runnable task, long delayMs);

    /** Runs {@code task} every {@code periodMs}, starting after {@code delayMs}. */
    void scheduleRepeating(String name, Runnable task, long delayMs, long periodMs);

    /** Cancels anything outstanding; safe to call more than once. */
    void cancel();

    /** The production implementation, one {@link Timer} per scheduled item. */
    static Scheduler timerBased() {
        return new TimerScheduler();
    }

    final class TimerScheduler implements Scheduler {
        private Timer timer;

        @Override
        public synchronized void scheduleOnce(String name, Runnable task, long delayMs) {
            replaceTimer(name);
            timer.schedule(wrap(task), delayMs);
        }

        @Override
        public synchronized void scheduleRepeating(String name, Runnable task,
                                                   long delayMs, long periodMs) {
            replaceTimer(name);
            timer.schedule(wrap(task), delayMs, periodMs);
        }

        private void replaceTimer(String name) {
            cancel();
            timer = new Timer(name);
        }

        /**
         * Runs {@code task}, absorbing anything it throws.
         *
         * <p>An uncaught throwable from a TimerTask terminates the Timer thread and cancels
         * everything still scheduled on it. For the reconnect path that is terminal: the retry
         * flag stays set, so every later connection-lost report is dropped as "already
         * retrying", and the tunnel stays down for the rest of the session with an ordinary
         * reconnect message as the last thing in the log.
         *
         * <p>Throwable rather than Exception on purpose -- the cases that kill a Timer are the
         * ones that are not Exceptions: OutOfMemoryError, NoClassDefFoundError from a class
         * loaded lazily on the failure path, StackOverflowError.
         */
        private static TimerTask wrap(Runnable task) {
            return new TimerTask() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable failure) {
                        Logger.getLogger(Scheduler.class.getName()).log(Level.SEVERE,
                                "Scheduled task failed; the scheduler stays alive", failure);
                    }
                }
            };
        }

        @Override
        public synchronized void cancel() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        }
    }
}
