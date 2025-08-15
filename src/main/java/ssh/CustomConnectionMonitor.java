package ssh;

import ch.ethz.ssh2.ConnectionMonitor;
import com.testingbot.tunnel.App;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author TestingBot
 */
public class CustomConnectionMonitor implements ConnectionMonitor {

    private final SSHTunnel tunnel;
    private final App app;
    private Timer timer;
    private boolean retrying = false;
    private long currentRetryDelay = 5000;
    private final int maxRetries = 3;

    public CustomConnectionMonitor(SSHTunnel tunnel, App app) {
        this.tunnel = tunnel;
        this.app = app;
    }

    @Override
    public void connectionLost(Throwable reason) {
        if (tunnel.isShuttingDown()) {
            return;
        }

        app.getHttpProxy().stop();

        Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.SEVERE,
            String.format("[%s] SSH Connection lost! %s", tunnel.getConnectionId(), reason.getMessage()));

        if (!this.retrying) {
            this.retrying = true;
            timer = new Timer("Reconnect-" + tunnel.getConnectionId());
            timer.schedule(new ReconnectTask(), currentRetryDelay);
        }
    }

    class ReconnectTask extends TimerTask {
        private int retryAttempts = 0;

        @Override
        public void run() {
            try {
                retryAttempts += 1;

                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO,
                    String.format("[%s] Attempting to re-establish SSH Connection (attempt %d, delay %dms)",
                        tunnel.getConnectionId(), retryAttempts, currentRetryDelay));

                tunnel.stop();
                tunnel.connect();

                if (tunnel.isAuthenticated()) {
                    // Successful reconnection
                    retrying = false;
                    retryAttempts = 0;
                    timer.cancel();

                    app.getHttpProxy().start();
                    tunnel.createPortForwarding();

                    Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO,
                        String.format("[%s] Successfully re-established SSH Connection after %d attempts",
                            tunnel.getConnectionId(), retryAttempts));
                    return;
                }

            } catch (Exception ex) {
                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.WARNING,
                    String.format("[%s] Reconnection attempt %d failed: %s",
                        tunnel.getConnectionId(), retryAttempts, ex.getMessage()));
            }

            // Check if we should continue retrying
            if (retryAttempts >= maxRetries) {
                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.WARNING,
                    String.format("[%s] Giving up retrying after %d attempts. Creating a new Tunnel Connection.",
                        tunnel.getConnectionId(), retryAttempts));

                // Give up connecting to this tunnel VM, try another one
                timer.cancel();
                retrying = false;
                retryAttempts = 0;

                app.stop();
                try {
                    app.boot();
                } catch (Exception ex) {
                    Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.SEVERE,
                        String.format("[%s] Failed to create new tunnel: %s", tunnel.getConnectionId(), ex.getMessage()), ex);
                }
            } else {
                timer.cancel();
                timer = new Timer("Reconnect-" + tunnel.getConnectionId());
                timer.schedule(new ReconnectTask(), currentRetryDelay);

                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO,
                    String.format("[%s] Will retry in %dms (attempt %d)",
                        tunnel.getConnectionId(), currentRetryDelay, retryAttempts + 1));
            }
        }
    }
}
