package ssh;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.LocalPortForwarder;
import com.testingbot.tunnel.App;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author TestingBot
 */
public class SSHTunnel {
    private App app;
    private Connection conn;
    private String server;
    private Timer timer;
    private boolean authenticated = false;
    private boolean shuttingDown = false;
    
    public SSHTunnel(App app, String server) throws Exception {
        /* Create a connection instance */
        this.app = app;
        this.server = server;
        
        this.conn = new Connection(server, 443);
        this.conn.addConnectionMonitor(new CustomConnectionMonitor(this, this.app));
        String[] ciphers = new String[]{"blowfish-cbc"};
        this.conn.setClient2ServerCiphers(ciphers);
        this.connect();
    }
    
    public final void connect() throws Exception {
        try {
            /* Now connect */
            conn.connect();
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, ex.getMessage(), ex);
        }
        
        try {
            // authenticate
            this.authenticated = conn.authenticateWithPassword(app.getClientKey(), app.getClientSecret());
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, "Failed authenticating to the tunnel. Please make sure you are supplying correct login credentials.");
            throw new Exception("Authentication failed: " + ex.getMessage());
        }
        
       
        if (this.authenticated == false) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, "Failed authenticating to the tunnel. Please make sure you are supplying correct login credentials.");
            throw new Exception("Authentication failed");
        }
        
        timer = new Timer();
        timer.schedule(new PollTask(), 60000, 60000);
    }
    
    public void stop(boolean quitting) {
        this.shuttingDown = true;
        this.stop();
    }
    
    public void stop() {
        timer.cancel();
        conn.close();
    }
    
    public void createPortForwarding() {
        try {
            conn.openSession();
            conn.requestRemotePortForwarding(server, 2010, "0.0.0.0", 8087);
            String hubHost = "hub.testingbot.com";
            LocalPortForwarder lpf1 = conn.createLocalPortForwarder(4446, hubHost, app.getHubPort());
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, "Could not setup port forwarding. Please make sure we can make an outbound connection to port 2010.");
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
    
    /**
     * @return the authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }
    
    class PollTask extends TimerTask {
        public void run() {
            try {
                // keep-alive attempt
                conn.sendIgnorePacket();
            } catch (IOException ex) {
                // ignore?
            }
        }
    }
}

