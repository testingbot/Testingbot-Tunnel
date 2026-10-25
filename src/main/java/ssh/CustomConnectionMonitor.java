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

    /**
     * Between reconnect attempts while the network is down: the first one that can get through
     * should not wait long once it is back (5 s -> 2 s took 2010's return after a 20-second outage
     * from 25.1 s to 23.6 s). MAX_RETRIES keeps the ~150 s before the tunnel is rebuilt.
     */
    static final long CURRENT_RETRY_DELAY = 2000;
    static final int MAX_RETRIES = 75;
    /**
     * The first reconnect goes out after this long, not after the full retry delay: the session
     * has already been gone for the keepalive's ~15 seconds, and every second more is a second
     * of failing tests.
     */
    static final long FIRST_RETRY_DELAY = 250;
    /** Between re-requests of the reverse forward on a session that is up. */
    static final long FORWARD_RETRY_DELAY = 2000;
    /** About two minutes of re-requests before the tunnel is rebuilt. */
    static final int MAX_FORWARD_RETRIES = 60;

    private final ReconnectableTunnel tunnel;
    private final ReconnectHost host;
    private final Scheduler scheduler;
    private final long retryDelayMs;

    private final AtomicBoolean retrying = new AtomicBoolean(false);
    private int retryAttempts = 0;
    private int forwardRetryAttempts = 0;
    private final Object lifecycleLock = new Object();
    private volatile boolean cancelled;

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
        synchronized (lifecycleLock) {
            if (isStopped()) {
                // A drop during a deliberate shutdown is expected, not something to recover from.
                return;
            }

            host.setReady(false);
            TunnelMetrics.ERRORS_TOTAL.labels("ssh_connection_lost").inc();

            host.stopLocalProxy();

            LOG.log(Level.SEVERE, String.format("[%s] SSH Connection lost! %s",
                    tunnel.getConnectionId(), reason == null ? "" : reason.getMessage()));

            // Only the first loss starts a retry cycle; further reports while one is already in
            // flight would otherwise stack up timers all racing to reconnect the same tunnel.
            if (retrying.compareAndSet(false, true)) {
                scheduleRetry(this::attemptReconnect, Math.min(FIRST_RETRY_DELAY, retryDelayMs));
            }
        }
    }

    private void scheduleRetry() {
        scheduleRetry(this::attemptReconnect);
    }

    private void scheduleRetry(Runnable task) {
        scheduleRetry(task, retryDelayMs);
    }

    private void scheduleRetry(Runnable task, long delayMs) {
        synchronized (lifecycleLock) {
            if (!isStopped()) {
                scheduler.scheduleOnce("Reconnect-" + tunnel.getConnectionId(), task, delayMs);
            }
        }
    }

    /** One reconnect attempt. Package-private so a test can drive it without the scheduler. */
    void attemptReconnect() {
        if (isStopped()) {
            return;
        }
        try {
            retryAttempts += 1;
            TunnelMetrics.TUNNEL_RECONNECTS_TOTAL.inc();

            LOG.log(Level.INFO, String.format(
                    "[%s] Attempting to re-establish SSH Connection (attempt %d, delay %dms)",
                    tunnel.getConnectionId(), retryAttempts, retryDelayMs));

            tunnel.stop();
            if (isStopped()) {
                return;
            }
            tunnel.connect();
            if (isStopped()) {
                tunnel.stop();
                return;
            }

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
        // Order matters: the proxy must be listening before the reverse forward is re-established,
        // or the first requests through it arrive with nothing to deliver to.
        //
        // Its failure is reported separately from the SSH retry accounting. A local port that has
        // not been released yet has nothing to do with the SSH session, which is up and
        // authenticated at this point; letting it fall into the caller's catch reported it as
        // "Reconnection attempt N failed", counted against the retry limit, and then tore down
        // and re-dialled a perfectly healthy connection -- turning a transient bind failure into
        // thirty pointless reconnects and a full tunnel rebuild, with nothing in the log naming
        // the port.
        try {
            synchronized (lifecycleLock) {
                if (isStopped()) {
                    return;
                }
                host.startLocalProxy();
            }
        } catch (RuntimeException proxyFailed) {
            LOG.log(Level.SEVERE, String.format(
                    "[%s] SSH reconnected, but the local proxy could not be restarted: %s",
                    tunnel.getConnectionId(), proxyFailed.getMessage()), proxyFailed);
            // Retry just the proxy. This used to call scheduleRetry(), whose task is
            // attemptReconnect() -- which begins tunnel.stop(); tunnel.connect(). So the branch
            // that exists to avoid re-dialling a healthy SSH session scheduled exactly that,
            // every five seconds, for as long as the port stayed bound.
            if (retryAttempts >= MAX_RETRIES) {
                // Checked here too. Returning from attemptReconnect() through onReconnected()
                // skips its own limit check, so a port that never frees up retried forever
                // rather than falling through to the rebuild that would have released it.
                giveUpAndRebuild();
                return;
            }
            scheduleRetry(this::retryLocalProxy);
            return;
        }

        finishReconnect();
    }

    /** The part common to a clean reconnect and one that needed the proxy retried. */
    private void finishReconnect() {
        if (isStopped()) {
            return;
        }
        tunnel.createPortForwarding();
        checkReconnected();
    }

    /** Ready when the session and the forwards are up; otherwise schedules what is missing. */
    private void checkReconnected() {
        synchronized (lifecycleLock) {
            if (isStopped()) {
                tunnel.stop();
                return;
            }
            if (!tunnel.isAuthenticated()) {
                host.setReady(false);
                LOG.log(Level.WARNING, "SSH dropped again while setting up port forwarding; retrying");
                if (retryAttempts >= MAX_RETRIES) {
                    giveUpAndRebuild();
                } else {
                    scheduleRetry();
                }
                return;
            }
            if (!tunnel.isForwardingEstablished()) {
                // The session is up; what failed is the forward. On a tunnel server this is
                // nearly always 2010 still held by the session this one replaces, which the
                // server releases once it notices that session is gone. Tearing the healthy
                // session down and re-dialling it every five seconds -- what this did -- made
                // each attempt slower and burned the retry budget, so a stale listener that
                // lived longer than ~3 minutes ended in a rebuild. Ask again on this session,
                // often, and rebuild only if the port never comes free.
                host.setReady(false);
                forwardRetryAttempts += 1;
                LOG.log(Level.WARNING, String.format(
                        "[%s] SSH reconnected but port forwarding failed; requesting it again (%d)",
                        tunnel.getConnectionId(), forwardRetryAttempts));
                if (forwardRetryAttempts >= MAX_FORWARD_RETRIES) {
                    forwardRetryAttempts = 0;
                    giveUpAndRebuild();
                } else {
                    scheduleRetry(this::retryForwarding, FORWARD_RETRY_DELAY);
                }
                return;
            }
            forwardRetryAttempts = 0;
            retrying.set(false);
            scheduler.cancel();
            host.setReady(true);
            LOG.log(Level.INFO, String.format(
                    "[%s] Successfully re-established SSH Connection after %d attempts",
                    tunnel.getConnectionId(), retryAttempts));
            retryAttempts = 0;
        }
    }

    /** Re-requests the forwards on the current session; reconnects if the session went away. */
    private void retryForwarding() {
        if (isStopped()) {
            return;
        }
        if (!tunnel.isAuthenticated()) {
            attemptReconnect();
            return;
        }
        tunnel.restartPortForwarding();
        checkReconnected();
    }

    /**
     * Restarts the local proxy without touching the SSH session.
     *
     * <p>Reached when SSH is up and authenticated but the proxy could not bind -- typically its
     * port has not been released yet. Everything else about the tunnel is healthy, so the
     * expensive part is not repeated; only when the port never frees up does this fall through
     * to the rebuild, which stops the whole App and so releases it.
     */
    private void retryLocalProxy() {
        if (isStopped()) {
            return;
        }
        retryAttempts += 1;
        try {
            synchronized (lifecycleLock) {
                if (isStopped()) {
                    return;
                }
                host.startLocalProxy();
            }
        } catch (RuntimeException proxyFailed) {
            LOG.log(Level.WARNING, String.format(
                    "[%s] Local proxy restart attempt %d failed: %s",
                    tunnel.getConnectionId(), retryAttempts, proxyFailed.getMessage()));
            if (retryAttempts >= MAX_RETRIES) {
                giveUpAndRebuild();
            } else {
                scheduleRetry(this::retryLocalProxy);
            }
            return;
        }
        finishReconnect();
    }

    private void giveUpAndRebuild() {
        if (isStopped()) {
            return;
        }
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

    /** Stops the retry scheduler, including callbacks racing its cancellation. */
    public void cancel() {
        synchronized (lifecycleLock) {
            cancelled = true;
            scheduler.cancel();
            retrying.set(false);
            host.setReady(false);
        }
    }

    private boolean isStopped() {
        return cancelled || tunnel.isShuttingDown();
    }

    /** Attempts made in the current retry cycle; for tests and diagnostics. */
    int getRetryAttempts() {
        return retryAttempts;
    }

    boolean isRetrying() {
        return retrying.get();
    }
}
