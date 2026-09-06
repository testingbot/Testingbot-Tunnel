package ssh;

import com.testingbot.tunnel.Api;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.TunnelFailedException;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;

/**
 *
 * @author TestingBot
 */
public class TunnelPoller {

    /** How long the tunnel is given to come up before the poller gives up. */
    static final long POLL_INTERVAL_MS = 5000;

    private final App app;
    private final Scheduler scheduler;
    private final String tunnelID;

    public TunnelPoller(App app, String tunnelID) {
        this(app, tunnelID, Scheduler.timerBased());
    }

    /**
     * @param scheduler how the poll is repeated. Injected so a test can run the poll directly
     *                  instead of sleeping through the five-second interval -- the previous
     *                  tests slept 6 and 16 seconds each and still could not tell a poller that
     *                  cancelled itself from one that never ran.
     */
    TunnelPoller(App app, String tunnelID, Scheduler scheduler) {
        this.app = app;
        this.tunnelID = tunnelID;
        this.scheduler = scheduler;
        scheduler.scheduleRepeating("TunnelPoller", new PollTask(),
                POLL_INTERVAL_MS, POLL_INTERVAL_MS);
    }

    public void cancel() {
        scheduler.cancel();
    }

    /**
     * Consecutive failed polls tolerated before the tunnel is given up on.
     *
     * <p>Not one. A poll is an HTTPS request to the API, and a single failed one means very
     * little -- a DNS hiccup or a dropped connection while the tunnel server is still booting.
     * Cancelling on the first exception turned any such blip into a process that stayed alive
     * and never became ready. Five consecutive failures spans about 25 seconds, by which point
     * it is not a blip.
     */
    static final int MAX_CONSECUTIVE_ERRORS = 5;

    /**
     * Stops polling and puts the app into its terminal failure state.
     *
     * <p>Cancelling the scheduler alone was the bug: it stopped the retries but told nothing
     * else, so the process stayed up with a metrics server answering and a tunnel that would
     * never be ready.
     */
    private void giveUp(String reason) {
        scheduler.cancel();
        app.setupFailed(reason, 1);
    }

    class PollTask implements Runnable {
        int counter = 0;
        int consecutiveErrors = 0;

        @Override
        public void run() {
            Api api = app.getApi();
            JsonNode response;
            try {
                response = api.pollTunnel(tunnelID);

                if (this.counter > 80) {
                    giveUp("Unable to create tunnel, waited for 400 seconds. Please try again or check https://status.testingbot.com");
                    return;
                }

                // Reset only after a poll that actually returned: the count is of consecutive
                // failures, and a run of them broken by one success is not the same thing.
                this.consecutiveErrors = 0;

                if (response.get("state").asText().equals("READY")) {
                   scheduler.cancel();
                   app.tunnelReady(response);
                } else {
                    this.counter += 1;
                    Logger.getLogger(TunnelPoller.class.getName()).log(Level.INFO, "Current tunnel status: {0}", response.get("state").asText());
                }
            } catch (TunnelFailedException tunnelFailedException) {
                // The tunnel became ready but could not be set up. Not retryable -- the failure
                // is in our own setup, not in the poll -- and this runs on a timer thread with
                // nobody to propagate to, so it ends the tunnel here.
                giveUp(tunnelFailedException.getMessage());
            } catch (Exception ex) {
                // A failed poll is not a failed tunnel. The scheduler keeps running so the next
                // tick retries, and only a sustained run of failures gives up.
                this.consecutiveErrors += 1;
                Logger.getLogger(TunnelPoller.class.getName()).log(Level.WARNING,
                        "Unable to poll for tunnel status ({0}/{1}): {2}",
                        new Object[]{this.consecutiveErrors, MAX_CONSECUTIVE_ERRORS,
                                     ex.getMessage()});
                if (this.consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    giveUp("Unable to poll for tunnel status after "
                            + MAX_CONSECUTIVE_ERRORS + " consecutive attempts: " + ex.getMessage());
                }
            }
        }
    }
}
