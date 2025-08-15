package ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.JSchException;
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
    private final JSch jsch;
    private Session session;
    private final String server;
    private final String connectionId;
    private Timer keepAliveTimer;
    private Timer connectionMonitorTimer;
    private boolean authenticated = false;
    private boolean shuttingDown = false;
    private CustomConnectionMonitor connectionMonitor;

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

    public final void connect() throws Exception {
        try {
            /* Now connect */
            long startTime = System.currentTimeMillis();
            session = jsch.getSession(app.getClientKey(), server, 443);
            session.setPassword(app.getClientSecret());
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();
            long connectTime = System.currentTimeMillis() - startTime;

            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Secure connection established in %dms", connectionId, connectTime));
        } catch (JSchException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE,
                String.format("[%s] Connection failed: %s", connectionId, ex.getMessage()), ex);
            throw new Exception("Connection failed: " + ex.getMessage());
        }

        // Authentication is done during connect() with JSch
        this.authenticated = session.isConnected();
        
        if (!this.authenticated) {
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
        if (connectionMonitorTimer != null) {
            connectionMonitorTimer.cancel();
        }

        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    public void createPortForwarding() {
        try {
            session.setPortForwardingR(2010, "0.0.0.0", app.getJettyPort());
            String hubHost = "hub.testingbot.com";
            session.setPortForwardingL(app.getSSHPort(), hubHost, app.getHubPort());

            Logger.getLogger(SSHTunnel.class.getName()).log(Level.INFO,
                String.format("[%s] Port forwarding established: %s:2010 -> localhost:%d, localhost:%d -> %s:%d",
                    connectionId, server, app.getJettyPort(), app.getSSHPort(), hubHost, app.getHubPort()));
        } catch (JSchException ex) {
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
                if (session != null && !session.isConnected() && !shuttingDown) {
                    connectionMonitor.connectionLost(new Exception("Connection lost"));
                }
            } catch (Exception ex) {
                Logger.getLogger(SSHTunnel.class.getName()).log(Level.WARNING,
                    String.format("[%s] Connection monitoring failed: %s", connectionId, ex.getMessage()));
            }
        }
    }
}

