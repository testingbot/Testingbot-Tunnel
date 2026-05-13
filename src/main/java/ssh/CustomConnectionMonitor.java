package ssh;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.TunnelMetrics;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author TestingBot
 */
public class CustomConnectionMonitor {

    private final SSHTunnel tunnel;
    private final App app;
    private Timer timer;
    private final AtomicBoolean retrying = new AtomicBoolean(false);
    private int retryAttempts = 0;
    private static final long CURRENT_RETRY_DELAY = 5000;
    private static final int MAX_RETRIES = 30;

    public CustomConnectionMonitor(SSHTunnel tunnel, App app) {
        this.tunnel = tunnel;
        this.app = app;
    }

    public void connectionLost(Throwable reason) {
        if (tunnel.isShuttingDown()) {
            return;
        }

        TunnelMetrics.setTunnelUp(false);
        TunnelMetrics.ERRORS_TOTAL.labels("ssh_connection_lost").inc();

        if (app.getHttpProxy() != null) {
            app.getHttpProxy().stop();
        }

        Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.SEVERE,
            String.format("[%s] SSH Connection lost! %s", tunnel.getConnectionId(), reason.getMessage()));

        if (this.retrying.compareAndSet(false, true)) {
            timer = new Timer("Reconnect-" + tunnel.getConnectionId());
            timer.schedule(new ReconnectTask(), CURRENT_RETRY_DELAY);
        }
    }

    private class ReconnectTask extends TimerTask {

        @Override
        public void run() {
            try {
                retryAttempts += 1;
                TunnelMetrics.TUNNEL_RECONNECTS_TOTAL.inc();

                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO,
                    String.format("[%s] Attempting to re-establish SSH Connection (attempt %d, delay %dms)",
                        tunnel.getConnectionId(), retryAttempts, CURRENT_RETRY_DELAY));

                tunnel.stop();
                tunnel.connect();

                if (tunnel.isAuthenticated()) {
                    // Successful reconnection
                    retrying.set(false);
                    timer.cancel();

                    if (app.getHttpProxy() != null) {
                        app.getHttpProxy().start();
                    }
                    tunnel.createPortForwarding();
                    TunnelMetrics.setTunnelUp(true);

                    Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO,
                        String.format("[%s] Successfully re-established SSH Connection after %d attempts",
                            tunnel.getConnectionId(), retryAttempts));
                    CustomConnectionMonitor.this.retryAttempts = 0;
                    return;
                }

            } catch (Exception ex) {
                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.WARNING,
                    String.format("[%s] Reconnection attempt %d failed: %s",
                        tunnel.getConnectionId(), retryAttempts, ex.getMessage()));
            }

            // Check if we should continue retrying
            if (retryAttempts >= MAX_RETRIES) {
                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.WARNING,
                    String.format("[%s] Giving up retrying after %d attempts. Creating a new Tunnel Connection.",
                        tunnel.getConnectionId(), retryAttempts));

                // Give up connecting to this tunnel VM, try another one
                timer.cancel();
                retrying.set(false);
                CustomConnectionMonitor.this.retryAttempts = 0;

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
                timer.schedule(new ReconnectTask(), CURRENT_RETRY_DELAY);

                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO,
                    String.format("[%s] Will retry in %dms (attempt %d)",
                        tunnel.getConnectionId(), CURRENT_RETRY_DELAY, retryAttempts + 1));
            }
        }
    }
}
