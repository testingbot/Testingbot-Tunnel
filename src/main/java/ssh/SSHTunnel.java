package ssh;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.LocalPortForwarder;
import com.testingbot.tunnel.App;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author TestingBot
 */
public class SSHTunnel {
    private final App app;
    private final Connection conn;
    private final String server;
    private final String connectionId;
    private Timer keepAliveTimer;
    private boolean authenticated = false;
    private boolean shuttingDown = false;
    private LocalPortForwarder lpf1;

    public SSHTunnel(App app, String server) throws Exception {
        /* Create a connection instance */
        this.app = app;
        this.server = server;
        this.connectionId = UUID.randomUUID().toString().substring(0, 8);

        this.conn = new Connection(server, 443);
        this.conn.addConnectionMonitor(new CustomConnectionMonitor(this, this.app));
        this.connect();
    }

    public final void connect() throws Exception {
        try {
            /* Now connect */
            long startTime = System.currentTimeMillis();
            conn.connect();
            long connectTime = System.currentTimeMillis() - startTime;

            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Secure connection established in %dms", connectionId, connectTime));
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Connection failed: %s", connectionId, ex.getMessage()), ex);
            throw ex;
        }

        try {
            // authenticate
            this.authenticated = conn.authenticateWithPassword(app.getClientKey(), app.getClientSecret());
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Failed authenticating to the tunnel. Please make sure you are supplying correct login credentials.", connectionId));
            throw new Exception("Authentication failed: " + ex.getMessage());
        }

        if (!this.authenticated) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Failed authenticating to the tunnel. Please make sure you are supplying correct login credentials.", connectionId));
            throw new Exception("Authentication failed");
        }

        // Start keep-alive timer with configurable interval
        keepAliveTimer = new Timer("KeepAlive-" + connectionId);
        keepAliveTimer.schedule(new KeepAliveTask(), 30000, 30000);
    }

    public void stop(boolean quitting) {
        this.shuttingDown = true;
        this.stop();
    }

    public void stop() {
        Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO, String.format("[%s] Stopping secure tunnel",connectionId));
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
        }

        conn.close();
        try {
            if (lpf1 != null) {
                lpf1.close();
            }
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Error closing port forwarder: %s", connectionId, ex.getMessage()), ex);
        }
    }

    public void createPortForwarding() {
        try {
            conn.openSession();
            conn.requestRemotePortForwarding(server, 2010, "0.0.0.0", app.getJettyPort());
            String hubHost = "hub.testingbot.com";
            lpf1 = conn.createLocalPortForwarder(app.getSSHPort(), hubHost, app.getHubPort());

            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Port forwarding established: %s:2010 -> localhost:%d, localhost:%d -> %s:%d",
                    connectionId, server, app.getJettyPort(), app.getSSHPort(), hubHost, app.getHubPort()));
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Could not setup port forwarding. Please make sure we can make an outbound connection to port 2010.", connectionId), ex);
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public String getConnectionId() {
        return connectionId;
    }

    /**
     * @return the authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    class KeepAliveTask extends TimerTask {
        @Override
        public void run() {
            try {
                long startTime = System.currentTimeMillis();
                conn.sendIgnorePacket();
                long roundTripTime = System.currentTimeMillis() - startTime;

                Logger.getLogger(SSHTunnel.class.getName()).log(Level.FINE,
                        String.format("[%s] Keep-alive sent, RTT: %dms", connectionId, roundTripTime));
            } catch (IOException ex) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                    String.format("[%s] Keep-alive failed: %s", connectionId, ex.getMessage()));
            }
        }
    }
}

