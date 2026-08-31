package ssh;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.TunnelMetrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rebuilds the SSH connection after it drops.
 *
 * <p>Retries a fixed number of times with a fixed delay. If the connection still cannot be
 * re-established, the tunnel server itself is presumed unhealthy and the whole tunnel is rebuilt
 * -- which usually lands on a different machine.
 *
 * <p>Collaborators are interfaces rather than concrete types so this can be tested without an SSH
 * server and without waiting out the real delays. That matters: this class only runs when
 * something has already gone wrong on a customer's network, which is exactly when it is hardest
 * to observe and most costly to get wrong.
 *
 * @author TestingBot
 */
public class CustomConnectionMonitor {

    private static final Logger LOG = Logger.getLogger(CustomConnectionMonitor.class.getName());

    static final long CURRENT_RETRY_DELAY = 5000;
    static final int MAX_RETRIES = 30;

    private final ReconnectableTunnel tunnel;
    private final ReconnectHost host;
    private final Scheduler scheduler;
    private final long retryDelayMs;

    private final AtomicBoolean retrying = new AtomicBoolean(false);
    private int retryAttempts = 0;

    public CustomConnectionMonitor(SSHTunnel tunnel, App app) {
        this(tunnel, ReconnectHost.of(app), Scheduler.timerBased(), CURRENT_RETRY_DELAY);
    }

    CustomConnectionMonitor(ReconnectableTunnel tunnel, ReconnectHost host, Scheduler scheduler,
                            long retryDelayMs) {
        this.tunnel = tunnel;
        this.host = host;
        this.scheduler = scheduler;
        this.retryDelayMs = retryDelayMs;
    }

    public void connectionLost(Throwable reason) {
        if (tunnel.isShuttingDown()) {
            // A drop during a deliberate shutdown is expected, not something to recover from.
            return;
        }

        TunnelMetrics.setTunnelUp(false);
        TunnelMetrics.ERRORS_TOTAL.labels("ssh_connection_lost").inc();

        host.stopLocalProxy();

        LOG.log(Level.SEVERE, String.format("[%s] SSH Connection lost! %s",
                tunnel.getConnectionId(), reason == null ? "" : reason.getMessage()));

        // Only the first loss starts a retry cycle; further reports while one is already in
        // flight would otherwise stack up timers all racing to reconnect the same tunnel.
        if (retrying.compareAndSet(false, true)) {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        scheduler.scheduleOnce("Reconnect-" + tunnel.getConnectionId(), this::attemptReconnect,
                retryDelayMs);
    }

    /** One reconnect attempt. Package-private so a test can drive it without the scheduler. */
    void attemptReconnect() {
        try {
            retryAttempts += 1;
            TunnelMetrics.TUNNEL_RECONNECTS_TOTAL.inc();

            LOG.log(Level.INFO, String.format(
                    "[%s] Attempting to re-establish SSH Connection (attempt %d, delay %dms)",
                    tunnel.getConnectionId(), retryAttempts, retryDelayMs));

            tunnel.stop();
            tunnel.connect();

            if (tunnel.isAuthenticated()) {
                onReconnected();
                return;
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, String.format("[%s] Reconnection attempt %d failed: %s",
                    tunnel.getConnectionId(), retryAttempts, ex.getMessage()));
        }

        if (retryAttempts >= MAX_RETRIES) {
            giveUpAndRebuild();
        } else {
            scheduleRetry();
            LOG.log(Level.INFO, String.format("[%s] Will retry in %dms (attempt %d)",
                    tunnel.getConnectionId(), retryDelayMs, retryAttempts + 1));
        }
    }

    private void onReconnected() {
        retrying.set(false);
        scheduler.cancel();

        // Order matters: the proxy must be listening before the reverse forward is re-established,
        // or the first requests through it arrive with nothing to deliver to.
        host.startLocalProxy();
        tunnel.createPortForwarding();
        TunnelMetrics.setTunnelUp(true);

        LOG.log(Level.INFO, String.format(
                "[%s] Successfully re-established SSH Connection after %d attempts",
                tunnel.getConnectionId(), retryAttempts));
        retryAttempts = 0;
    }

    private void giveUpAndRebuild() {
        LOG.log(Level.WARNING, String.format(
                "[%s] Giving up retrying after %d attempts. Creating a new Tunnel Connection.",
                tunnel.getConnectionId(), retryAttempts));

        scheduler.cancel();
        retrying.set(false);
        retryAttempts = 0;

        try {
            host.rebuildTunnel();
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, String.format("[%s] Failed to create new tunnel: %s",
                    tunnel.getConnectionId(), ex.getMessage()), ex);
        }
    }

    /** Attempts made in the current retry cycle; for tests and diagnostics. */
    int getRetryAttempts() {
        return retryAttempts;
    }

    boolean isRetrying() {
        return retrying.get();
    }
}
