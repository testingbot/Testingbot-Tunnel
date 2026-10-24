package ssh;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.ProxySOCKS5;
import com.testingbot.tunnel.proxy.ProxySpec;
import com.testingbot.tunnel.proxy.SshHttpProxy;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.JSchException;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.ForwardProbeHandler;
import com.testingbot.tunnel.TunnelMetrics;
import io.prometheus.client.Histogram;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
    private volatile Session session;
    private final Object lifecycleLock = new Object();
    private final String server;
    private final String connectionId;
    private Timer keepAliveTimer;
    private Timer connectionMonitorTimer;
    private Timer portForwardingMonitorTimer;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CustomConnectionMonitor connectionMonitor;
    private volatile boolean portForwardingEstablished = false;
    /** Set once a reverse-forward probe has succeeded on this session: the server permits it. */
    private volatile boolean probeSupported;
    private int probeFailures;

    /** Long enough for a slow network and a proxy hop, short enough to fail rather than hang. */
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 30_000;

    /** TestingBot's tunnel servers listen for SSH on 443, so egress firewalls let it out. */
    static final int DEFAULT_SSH_PORT = 443;

    /** Where the Selenium forward is delivered, resolved by the tunnel server, not by us. */
    static final String DEFAULT_HUB_HOST = "hub.testingbot.com";

    /** The port the tunnel server accepts browser traffic on and forwards back to us. */
    static final int DEFAULT_REMOTE_PROXY_PORT = 2010;

    /**
     * How quickly a dead session is noticed: JSch sends a keepalive after this long without a
     * packet from the server, and drops the session after {@link #SERVER_ALIVE_COUNT_MAX}
     * unanswered ones.
     *
     * <p>Without it nothing read-side ever timed out. The 30-second keepalive below only writes,
     * so after a NAT change or a laptop switching networks the session looked connected until the
     * operating system gave up retransmitting -- about 15 minutes on Linux -- and all that time
     * the tunnel server kept port 2010 for a client that was gone.
     *
     * <p>2 s x 3: a session that hears nothing at all from the server for ~6 s is dead. That is
     * the floor of how fast port 2010 can come back after a network change, so it is kept short;
     * the server answers every keepalive at once, so a healthy connection - busy or idle - is
     * never that quiet. Measured on a NAT change: 2010 back after ~8 s (5 s x 3: ~18 s); a
     * 4-second outage passes without a reconnect, and nothing reconnected under load.
     */
    static final int SERVER_ALIVE_INTERVAL_MS = 2_000;
    static final int SERVER_ALIVE_COUNT_MAX = 3;

    /** How often the connection and forwarding monitors look. */
    static final long CONNECTION_CHECK_INTERVAL_MS = 500;
    static final long FORWARDING_CHECK_INTERVAL_MS = 5_000;

    /** How long one end-to-end probe of the reverse forward may take. */
    static final int REVERSE_PROBE_TIMEOUT_MS = 5_000;
    /** Consecutive failed probes before the reverse forward is re-registered. */
    static final int REVERSE_PROBE_FAILURES_BEFORE_REPAIR = 2;

    private final int sshPort;
    private final String hubHost;
    private final int remoteProxyPort;

    public SSHTunnel(App app, String server) throws Exception {
        this(app, server, DEFAULT_SSH_PORT, DEFAULT_HUB_HOST, DEFAULT_REMOTE_PROXY_PORT);
    }

    SSHTunnel(App app, String server, int sshPort, String hubHost) throws Exception {
        this(app, server, sshPort, hubHost, DEFAULT_REMOTE_PROXY_PORT);
    }

    /**
     * @param sshPort         the tunnel server's SSH port
     * @param hubHost         where the local forward is delivered
     * @param remoteProxyPort the port the tunnel server listens on for browser traffic
     *
     * <p>All three are fixed in production. They are parameters so the session handling can be
     * tested against an in-process SSH server on an ephemeral port, forwarding to a local stand-in
     * for the hub -- otherwise none of this code is reachable without the live service. The
     * reverse forward's port is one of them because two tunnel servers are two machines there,
     * while in one JVM they are two listeners that cannot both bind 2010.
     */
    SSHTunnel(App app, String server, int sshPort, String hubHost, int remoteProxyPort)
            throws Exception {
        /* Create a connection instance */
        this.app = app;
        this.server = server;
        this.sshPort = sshPort;
        this.hubHost = hubHost;
        this.remoteProxyPort = remoteProxyPort;
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
        ProxySpec spec = ProxySpec.parse(app.getControlProxy());
        if (spec == null) {
            return;
        }
        if (spec.isSocks5()) {
            ProxySOCKS5 socks = new ProxySOCKS5(spec.getHost(), spec.getPort());
            String[] credentials = splitCredentials(app.getControlProxyAuth());
            if (credentials != null) {
                socks.setUserPasswd(credentials[0], credentials[1]);
            }
            session.setProxy(socks);
        } else {
            // Our own CONNECT rather than JSch's ProxyHTTP, which only speaks Basic.
            session.setProxy(new SshHttpProxy(spec.getHost(), spec.getPort(),
                    app.controlProxyAuthenticator()));
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

    /**
     * Decides what the server has to prove before it receives the account secret.
     *
     * <p>With pins configured the session verifies against them and a mismatch aborts the
     * connect. Without pins there is nothing to verify against, so the previous behaviour stands
     * -- any key is accepted -- and that fact is logged rather than left implicit: the password
     * on this session is the customer's account secret, and an unverified server is one that
     * could be anybody.
     */
    private void applyHostKeyVerification(Session session) throws JSchException {
        HostKeyPins pins = app.getSshHostKeyPins();
        if (pins == null || pins.isEmpty()) {
            if (app.requiresVerifiedSshHostKey()) {
                // Asked for by --ssh-host-key-policy require: refuse before setPassword's value
                // can reach a server nothing has vouched for.
                throw new JSchException(String.format(
                    "No host key fingerprint for %s:%d, and --ssh-host-key-policy is 'require'. "
                        + "Supply one with --ssh-host-key, or use 'warn' to connect unverified.",
                    server, sshPort));
            }
            session.setConfig("StrictHostKeyChecking", "no");
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                String.format("[%s] The tunnel server's host key is not verified: no fingerprint "
                    + "was configured with --ssh-host-key and the API supplied none. The account "
                    + "secret is this connection's password, so anything answering %s:%d "
                    + "receives it. Use --ssh-host-key-policy require to refuse this.",
                    connectionId, server, sshPort));
            return;
        }
        session.setHostKeyRepository(new PinnedHostKeyRepository(pins));
        session.setConfig("StrictHostKeyChecking", "yes");
        Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
            String.format("[%s] Verifying the tunnel server against %d pinned host key(s)",
                connectionId, pins.size()));
    }

    /** Milliseconds allowed for the dial and handshake; {@code --http-dial-timeout} when set. */
    private int connectTimeoutMs() {
        Integer seconds = app.getHttpDialTimeoutSeconds();
        return seconds == null ? DEFAULT_CONNECT_TIMEOUT_MS : seconds * 1000;
    }

    public final void connect() throws Exception {
        if (shuttingDown.get()) {
            throw new IllegalStateException("Tunnel has been stopped");
        }
        Histogram.Timer histogramTimer = TunnelMetrics.TUNNEL_CONNECT_DURATION_SECONDS.startTimer();
        try {
            /* Now connect */
            long startTime = System.currentTimeMillis();
            session = jsch.getSession(app.getClientKey(), server, sshPort);
            probeSupported = false;
            probeFailures = 0;
            session.setPassword(app.getClientSecret());
            applyHostKeyVerification(session);
            applyUpstreamProxy(session);
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
            // Bounded, because connect() covers the TCP dial, the proxy's CONNECT and the SSH
            // banner exchange: a peer that accepts the socket and then says nothing -- a
            // silently dropping firewall, a proxy holding the connection -- otherwise left this
            // blocked with no timeout at all, on the startup path and on every reconnect.
            session.connect(connectTimeoutMs());
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

        synchronized (lifecycleLock) {
            if (shuttingDown.get()) {
                session.disconnect();
                throw new IllegalStateException("Tunnel stopped while connecting");
            }
            keepAliveTimer = new Timer("KeepAlive-" + connectionId);
            keepAliveTimer.schedule(new KeepAliveTask(), 30000, 30000);
            connectionMonitorTimer = new Timer("ConnectionMonitor-" + connectionId);
            connectionMonitorTimer.schedule(new ConnectionMonitorTask(),
                    CONNECTION_CHECK_INTERVAL_MS, CONNECTION_CHECK_INTERVAL_MS);
            portForwardingMonitorTimer = new Timer("PortForwardingMonitor-" + connectionId);
            portForwardingMonitorTimer.schedule(new PortForwardingMonitorTask(),
                    FORWARDING_CHECK_INTERVAL_MS, FORWARDING_CHECK_INTERVAL_MS);
        }
    }

    public void stop(boolean quitting) {
        if (quitting) {
            this.shuttingDown.set(true);
            connectionMonitor.cancel();
        }
        this.stop();
    }

    public void stop() {
        synchronized (lifecycleLock) {
            portForwardingEstablished = false;
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

            if (session != null) {
                session.disconnect();
            }
        }
    }

    @Override
    public boolean isForwardingEstablished() {
        return portForwardingEstablished && isAuthenticated() && !shuttingDown.get();
    }

    // Delivery target for the reverse forward. JSch connects to this host for every
    // forwarded-tcpip channel; "0.0.0.0" is not connectable on macOS (TB-253), and
    // "localhost" could resolve to ::1 while Jetty listens on IPv4 only.
    static final String REVERSE_FORWARD_HOST = "127.0.0.1";

    public void createPortForwarding() {
        // The two directions are established independently. Doing both in one try meant a
        // failure setting up the reverse forward skipped the local one entirely -- which is
        // exactly what happened on every restart, because the reverse forward was still
        // registered on the server and re-registering it throws.
        boolean reverseOk = establishReverseForward();
        boolean localOk = establishLocalForward();

        portForwardingEstablished = reverseOk && localOk;
        if (portForwardingEstablished) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Port forwarding established: %s:%d -> %s:%d, localhost:%d -> %s:%d",
                    connectionId, server, remoteProxyPort, REVERSE_FORWARD_HOST, app.getJettyPort(), app.getSSHPort(), hubHost, app.getHubPort()));
        }
    }

    private boolean establishReverseForward() {
        try {
            session.setPortForwardingR(remoteProxyPort, REVERSE_FORWARD_HOST, app.getJettyPort());
            return true;
        } catch (JSchException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Could not setup port forwarding. Please make sure we can make an outbound connection to port %d.", connectionId, remoteProxyPort), ex);
            return false;
        }
    }

    private boolean establishLocalForward() {
        try {
            session.setPortForwardingL(app.getSSHPort(), hubHost, app.getHubPort());
            return true;
        } catch (JSchException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Could not forward local port %d to %s:%d", connectionId,
                    app.getSSHPort(), hubHost, app.getHubPort()), ex);
            return false;
        }
    }

    /** Drops both forwards, ignoring ones that are not registered. */
    private void removePortForwarding() {
        try {
            session.delPortForwardingR(remoteProxyPort);
        } catch (Exception notRegistered) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.FINE,
                String.format("[%s] Reverse forward was not registered", connectionId));
        }
        try {
            session.delPortForwardingL(app.getSSHPort());
        } catch (Exception notRegistered) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.FINE,
                String.format("[%s] Local forward was not registered", connectionId));
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

    /**
     * Sends a request into port 2010 on the tunnel server and checks it comes back out of this
     * client's local proxy.
     *
     * <p>{@link #verifyReverseForwardDelivery()} only proves the local end. What fails in
     * practice is the server end: the forward was never registered because an older session of
     * the same account still held 2010, or the listener on 2010 belongs to a session whose client
     * is gone. In both cases the tunnel looked healthy here while every device request through
     * it failed. This opens a direct-tcpip channel to the server's own 127.0.0.1:2010 -- exactly
     * where squid and the devices connect -- and sends a request only our proxy answers with this
     * nonce.
     *
     * @return true when the probe made the round trip through this client's reverse forward
     */
    boolean probeReverseForward(int timeoutMs) {
        Session current = session;
        if (current == null || !current.isConnected()) {
            return false;
        }
        String nonce = UUID.randomUUID().toString();
        ChannelDirectTCPIP channel = null;
        try {
            channel = (ChannelDirectTCPIP) current.openChannel("direct-tcpip");
            channel.setHost("127.0.0.1");
            channel.setPort(remoteProxyPort);
            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();
            channel.connect(timeoutMs);
            out.write(("GET http://" + ForwardProbeHandler.PROBE_HOST + "/ HTTP/1.1\r\n"
                    + "Host: " + ForwardProbeHandler.PROBE_HOST + "\r\n"
                    + ForwardProbeHandler.PROBE_HEADER + ": " + nonce + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String expected = ForwardProbeHandler.PROBE_HEADER.toLowerCase(Locale.ROOT) + ": " + nonce;
            StringBuilder answer = new StringBuilder();
            byte[] buffer = new byte[1024];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline && answer.length() < 8192) {
                if (in.available() > 0) {
                    int n = in.read(buffer);
                    if (n < 0) {
                        break;
                    }
                    answer.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
                    if (answer.toString().toLowerCase(Locale.ROOT).contains(expected)) {
                        return true;
                    }
                } else if (channel.isClosed()) {
                    break;
                } else {
                    Thread.sleep(20);
                }
            }
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failed) {
            // The channel could not be opened: nothing listens on 2010, or the server does not
            // permit the probe. Which of the two is decided by the caller.
            return false;
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    /** The live JSch session; package-private so tests can inspect the real forwarding tables. */
    Session getSession() {
        return session;
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
     * <p>The entries look like {@code "4446:hub.testingbot.com:80"}, or with a bind address
     * {@code "127.0.0.1:4446:hub.testingbot.com:80"}. The local port is what identifies the
     * forward, so that is what is compared.
     *
     * <p>This was {@code contains(String.valueOf(sshPort))} on the whole entry, and a previous
     * test documented the false positive it produces -- 445 matching 4456 -- as harmless. It is
     * not: this answers "is my forward still there", and the monitor repairs it when the answer
     * is no. A match against some other forward's port, or against a digit sequence in the
     * destination host or the remote port, reports a forward that is gone as present, so the
     * repair never runs and every request through the tunnel keeps failing.
     */
    static boolean localForwardingActive(String[] forwardedPorts, int sshPort) {
        if (forwardedPorts == null) {
            return false;
        }
        for (String entry : forwardedPorts) {
            if (entry != null && localPortOf(entry) == sshPort) {
                return true;
            }
        }
        return false;
    }

    /**
     * The local port from a JSch forwarding entry, or -1 when it cannot be read.
     *
     * <p>The port is the field before the destination host: first for {@code lport:host:rport},
     * second when a bind address is present. Distinguished by which one parses as a number --
     * a bind address never does, and a port always does.
     */
    private static int localPortOf(String entry) {
        String[] fields = entry.split(":");
        if (fields.length < 3) {
            return -1;
        }
        // fields[0] is either the local port or a bind address.
        for (int i = 0; i < 2 && i < fields.length; i++) {
            try {
                return Integer.parseInt(fields[i].trim());
            } catch (NumberFormatException notAPort) {
                // a bind address; try the next field
            }
        }
        return -1;
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

    /**
     * The monitor the connection task drives.
     *
     * <p>Exposed for tests: the task's whole purpose is to call {@code connectionLost} when the
     * session goes away, and without this they could only assert that running it threw nothing,
     * which an empty method body satisfies.
     */
    CustomConnectionMonitor connectionMonitor() {
        return connectionMonitor;
    }

    class PortForwardingMonitorTask extends TimerTask {
        private final ReverseHealthTracker reverseHealth = new ReverseHealthTracker();

        /** Exposed for the same reason as {@link SSHTunnel#connectionMonitor()}. */
        ReverseHealthTracker reverseHealth() {
            return reverseHealth;
        }

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

                    // A reconnect in flight owns the forwards; repairing them in parallel would
                    // race its own attempt.
                    if (!connectionMonitor.isRetrying()) {
                        checkRemoteForward();
                    }
                }
            } catch (Exception ex) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                    String.format("[%s] Port forwarding monitoring failed: %s", connectionId, ex.getMessage()));
            }
        }
    }

    /**
     * Keeps port 2010 on the tunnel server pointing at this client.
     *
     * <p>A reverse forward that could not be registered -- typically because an older session of
     * the same account still held 2010 -- was never asked for again while the SSH session stayed
     * up, so the tunnel stayed up, not ready, and carrying nothing until the client was
     * restarted. Now it is re-requested on every check until the server lets it go. A forward
     * that is registered but no longer delivers (the probe fails twice in a row) is re-registered
     * the same way.
     */
    private void checkRemoteForward() {
        if (!portForwardingEstablished) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING, String.format(
                    "[%s] Port forwarding is not established; requesting it again", connectionId));
            restartPortForwarding();
            if (portForwardingEstablished) {
                app.reverseForwardRestored();
            }
            return;
        }
        if (probeReverseForward(REVERSE_PROBE_TIMEOUT_MS)) {
            probeSupported = true;
            probeFailures = 0;
            return;
        }
        if (!probeSupported) {
            // Never succeeded on this session: the server does not allow the probe (an older
            // image's PermitOpen), so a failure says nothing about the forward.
            return;
        }
        probeFailures += 1;
        if (probeFailures >= REVERSE_PROBE_FAILURES_BEFORE_REPAIR) {
            probeFailures = 0;
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, String.format(
                    "[%s] Port %d on the tunnel server no longer reaches this client; re-registering it",
                    connectionId, remoteProxyPort));
            restartPortForwarding();
            if (portForwardingEstablished) {
                app.reverseForwardRestored();
            }
        }
    }

    @Override
    public synchronized void restartPortForwarding() {
        try {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Restarting port forwarding", connectionId));

            // Drop whatever is still registered first. Without this the reverse forward is
            // still bound on the server, re-registering it fails, and the local forward this
            // restart exists to restore is never re-created.
            portForwardingEstablished = false;
            removePortForwarding();

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

