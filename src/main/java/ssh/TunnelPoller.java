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

    class PollTask implements Runnable {
        int counter = 0;

        @Override
        public void run() {
            Api api = app.getApi();
            JsonNode response;
            try {
                response = api.pollTunnel(tunnelID);

                if (this.counter > 80) {
                    Logger.getLogger(TunnelPoller.class.getName()).log(Level.SEVERE, "Unable to create tunnel, waited for 400 seconds. Please try again or check https://status.testingbot.com");
                    scheduler.cancel();
                    return;
                }

                if (response.get("state").asText().equals("READY")) {
                   scheduler.cancel();
                   app.tunnelReady(response);
                } else {
                    this.counter += 1;
                    Logger.getLogger(TunnelPoller.class.getName()).log(Level.INFO, "Current tunnel status: {0}", response.get("state").asText());
                }
            } catch (TunnelFailedException tunnelFailedException) {
                // the tunnel became ready but could not be set up; this runs on a
                // timer thread so there is nobody to propagate to, report it here
                scheduler.cancel();
                Logger.getLogger(TunnelPoller.class.getName()).log(Level.SEVERE, tunnelFailedException.getMessage());
            } catch (Exception ex) {
                scheduler.cancel();
                Logger.getLogger(TunnelPoller.class.getName()).log(Level.SEVERE, "Unable to poll for tunnel status.");
            }
        }
    }
}
