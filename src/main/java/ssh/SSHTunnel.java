package ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.ProxySOCKS5;
import com.testingbot.tunnel.proxy.ProxySpec;
import com.testingbot.tunnel.proxy.SshHttpProxy;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.JSchException;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.TunnelMetrics;
import io.prometheus.client.Histogram;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author TestingBot
 */
public class SSHTunnel implements ReconnectableTunnel {
    private final App app;
    private final JSch jsch;
    private Session session;
    private final String server;
    private final String connectionId;
    private Timer keepAliveTimer;
    private Timer connectionMonitorTimer;
    private Timer portForwardingMonitorTimer;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CustomConnectionMonitor connectionMonitor;
    private boolean portForwardingEstablished = false;

    public SSHTunnel(App app, String server) throws Exception {
        /* Create a connection instance */
        this.app = app;
        this.server = server;
        this.connectionId = UUID.randomUUID().toString().substring(0, 8);

        this.jsch = new JSch();
        this.session = null;
        this.connectionMonitor = new CustomConnectionMonitor(this, this.app);
        this.connect();
    }

    /**
     * Routes the SSH connection through {@code --proxy} when one is configured.
     *
     * <p>Until TB-321 the SSH connection ignored --proxy entirely, so on a network whose only
     * egress is a proxy the tunnel could not connect at all -- the browser traffic settings were
     * irrelevant because the control connection never came up.
     */
    private void applyUpstreamProxy(Session session) {
        ProxySpec spec = ProxySpec.parse(app.getProxy());
        if (spec == null) {
            return;
        }
        if (spec.isSocks5()) {
            ProxySOCKS5 socks = new ProxySOCKS5(spec.getHost(), spec.getPort());
            String[] credentials = splitCredentials(app.getProxyAuth());
            if (credentials != null) {
                socks.setUserPasswd(credentials[0], credentials[1]);
            }
            session.setProxy(socks);
        } else {
            // Our own CONNECT rather than JSch's ProxyHTTP, which only speaks Basic.
            session.setProxy(new SshHttpProxy(spec.getHost(), spec.getPort(),
                    app.proxyAuthenticator()));
        }
        Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
            String.format("[%s] SSH connection will traverse the upstream proxy %s:%d",
                connectionId, spec.getHost(), spec.getPort()));
    }

    /** Splits "user:password", tolerating a colon inside the password. */
    static String[] splitCredentials(String userPassword) {
        if (userPassword == null || userPassword.isEmpty()) {
            return null;
        }
        int colon = userPassword.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return new String[]{userPassword.substring(0, colon), userPassword.substring(colon + 1)};
    }

    public final void connect() throws Exception {
        Histogram.Timer histogramTimer = TunnelMetrics.TUNNEL_CONNECT_DURATION_SECONDS.startTimer();
        try {
            /* Now connect */
            long startTime = System.currentTimeMillis();
            session = jsch.getSession(app.getClientKey(), server, 443);
            session.setPassword(app.getClientSecret());
            session.setConfig("StrictHostKeyChecking", "no");
            applyUpstreamProxy(session);
            session.connect();
            long connectTime = System.currentTimeMillis() - startTime;

            TunnelMetrics.TUNNEL_CONNECTS_TOTAL.inc();

            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Secure connection established in %dms", connectionId, connectTime));
        } catch (JSchException ex) {
            TunnelMetrics.ERRORS_TOTAL.labels("ssh_connect").inc();
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Connection failed: %s", connectionId, ex.getMessage()), ex);
            throw new Exception("Connection failed: " + ex.getMessage());
        } finally {
            histogramTimer.observeDuration();
        }

        // Authentication is done during connect() with JSch
        boolean authenticated = session.isConnected();

        if (!authenticated) {
            TunnelMetrics.ERRORS_TOTAL.labels("ssh_auth").inc();
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Failed authenticating to the tunnel. Please make sure you are supplying correct login credentials.", connectionId));
            throw new Exception("Authentication failed");
        }

        // Start keep-alive timer with configurable interval
        keepAliveTimer = new Timer("KeepAlive-" + connectionId);
        keepAliveTimer.schedule(new KeepAliveTask(), 30000, 30000);

        // Start connection monitoring timer
        connectionMonitorTimer = new Timer("ConnectionMonitor-" + connectionId);
        connectionMonitorTimer.schedule(new ConnectionMonitorTask(), 10000, 10000);

        // Start port forwarding monitoring timer
        portForwardingMonitorTimer = new Timer("PortForwardingMonitor-" + connectionId);
        portForwardingMonitorTimer.schedule(new PortForwardingMonitorTask(), 15000, 15000);
    }

    public void stop(boolean quitting) {
        this.shuttingDown.set(true);
        this.stop();
    }

    public void stop() {
        Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO, String.format("[%s] Stopping secure tunnel", connectionId));
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
        }
        if (connectionMonitorTimer != null) {
            connectionMonitorTimer.cancel();
        }
        if (portForwardingMonitorTimer != null) {
            portForwardingMonitorTimer.cancel();
        }

        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    // Delivery target for the reverse forward. JSch connects to this host for every
    // forwarded-tcpip channel; "0.0.0.0" is not connectable on macOS (TB-253), and
    // "localhost" could resolve to ::1 while Jetty listens on IPv4 only.
    static final String REVERSE_FORWARD_HOST = "127.0.0.1";

    public void createPortForwarding() {
        try {
            session.setPortForwardingR(2010, REVERSE_FORWARD_HOST, app.getJettyPort());
            String hubHost = "hub.testingbot.com";
            session.setPortForwardingL(app.getSSHPort(), hubHost, app.getHubPort());

            portForwardingEstablished = true;
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Port forwarding established: %s:2010 -> %s:%d, localhost:%d -> %s:%d",
                    connectionId, server, REVERSE_FORWARD_HOST, app.getJettyPort(), app.getSSHPort(), hubHost, app.getHubPort()));
        } catch (JSchException ex) {
            portForwardingEstablished = false;
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Could not setup port forwarding. Please make sure we can make an outbound connection to port 2010.", connectionId), ex);
        }
    }

    /**
     * Verifies the reverse forward can actually deliver traffic by performing the
     * same raw socket connect JSch does for every forwarded-tcpip channel. JSch
     * swallows connect failures silently, so without this check a dead reverse
     * forward looks healthy (TB-253). Only meaningful once the local proxy is
     * listening on the jetty port.
     */
    public boolean verifyReverseForwardDelivery() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(REVERSE_FORWARD_HOST, app.getJettyPort()), 5000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    public String getConnectionId() {
        return connectionId;
    }

    /**
     * @return the authenticated
     */
    public boolean isAuthenticated() {
        return session != null && session.isConnected();
    }

    class KeepAliveTask extends TimerTask {
        @Override
        public void run() {
            try {
                if (session != null && session.isConnected()) {
                    long startTime = System.currentTimeMillis();
                    session.sendKeepAliveMsg();
                    long roundTripTime = System.currentTimeMillis() - startTime;

                    Logger.getLogger(SSHTunnel.class.getName()).log(Level.FINE,
                        String.format("[%s] Keep-alive sent, RTT: %dms", connectionId, roundTripTime));
                }
            } catch (Exception ex) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                    String.format("[%s] Keep-alive failed: %s", connectionId, ex.getMessage()));
            }
        }
    }

    class ConnectionMonitorTask extends TimerTask {
        @Override
        public void run() {
            try {
                if (session != null && !session.isConnected() && !shuttingDown.get()) {
                    connectionMonitor.connectionLost(new Exception("Connection lost"));
                }
            } catch (Exception ex) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                    String.format("[%s] Connection monitoring failed: %s", connectionId, ex.getMessage()));
            }
        }
    }

    /**
     * Is the local forward for {@code sshPort} still in JSch's list?
     *
     * <p>Extracted so it can be tested: the entries are free-form strings like
     * {@code "4446:hub.testingbot.com:80"}, and a substring match on the port is looser than it
     * looks -- 445 matches 4456 -- so the exact behaviour is worth pinning rather than assuming.
     */
    static boolean localForwardingActive(String[] forwardedPorts, int sshPort) {
        if (forwardedPorts == null) {
            return false;
        }
        for (String port : forwardedPorts) {
            if (port != null && port.contains(String.valueOf(sshPort))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tracks reverse-forward health so each change is logged once rather than every 15 seconds.
     *
     * <p>A broken reverse forward means every request through the tunnel fails, so it must be
     * reported -- but repeating it on every poll buries everything else in the log.
     */
    static final class ReverseHealthTracker {
        enum Change { UNCHANGED, BROKEN, RESTORED }

        private boolean healthy = true;

        Change update(boolean deliveryOk) {
            Change change = Change.UNCHANGED;
            if (!deliveryOk && healthy) {
                change = Change.BROKEN;
            } else if (deliveryOk && !healthy) {
                change = Change.RESTORED;
            }
            healthy = deliveryOk;
            return change;
        }

        boolean isHealthy() {
            return healthy;
        }
    }

    class PortForwardingMonitorTask extends TimerTask {
        private final ReverseHealthTracker reverseHealth = new ReverseHealthTracker();

        @Override
        public void run() {
            try {
                if (session != null && session.isConnected() && !shuttingDown.get()) {
                    // Check if port forwarding is still active by testing the forwarded ports
                    boolean localForwardingActive =
                            localForwardingActive(session.getPortForwardingL(), app.getSSHPort());

                    if (!localForwardingActive && portForwardingEstablished) {
                        Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                            String.format("[%s] Local port forwarding lost, attempting to restart", connectionId));
                        restartPortForwarding();
                    }

                    // The reverse forward (2010 -> local jetty port) has no JSch-side
                    // status API, so probe the delivery target directly.
                    switch (reverseHealth.update(verifyReverseForwardDelivery())) {
                        case BROKEN -> Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                            String.format("[%s] Reverse port forwarding is broken: cannot connect to %s:%d. Traffic through the tunnel will fail until a local proxy is listening on this port.",
                                connectionId, REVERSE_FORWARD_HOST, app.getJettyPort()));
                        case RESTORED -> Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                            String.format("[%s] Reverse port forwarding delivery to %s:%d restored", connectionId, REVERSE_FORWARD_HOST, app.getJettyPort()));
                        default -> { }
                    }
                }
            } catch (Exception ex) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                    String.format("[%s] Port forwarding monitoring failed: %s", connectionId, ex.getMessage()));
            }
        }
    }

    private void restartPortForwarding() {
        try {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Restarting port forwarding", connectionId));

            // Clear existing port forwarding
            portForwardingEstablished = false;

            // Re-establish port forwarding
            createPortForwarding();

            if (portForwardingEstablished) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                    String.format("[%s] Port forwarding successfully restarted", connectionId));
            } else {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                    String.format("[%s] Failed to restart port forwarding", connectionId));
            }
        } catch (Exception ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Error during port forwarding restart: %s", connectionId, ex.getMessage()), ex);
        }
    }
}

