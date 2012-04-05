/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ssh;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.LocalPortForwarder;
import com.testingbot.tunnel.App;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author jochen
 */
public class SSHTunnel {
    private App app;
    private Connection conn;
    private String server;
    private boolean authenticated = false;
    
    public SSHTunnel(App app, String server) throws Exception {
        /* Create a connection instance */
        this.app = app;
        this.server = server;
        
        this.conn = new Connection(server, 443);
        try {
            /* Now connect */
            conn.connect();
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, null, ex);
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
    }
    
    public void stop() {
        conn.close();
    }
    
    public void createPortForwarding() {
        try {
            conn.openSession();
            conn.requestRemotePortForwarding(server, 2010, "0.0.0.0", 8087);
            LocalPortForwarder lpf1 = conn.createLocalPortForwarder(4446, "hub.testingbot.com", 4444);
        } catch (IOException ex) {
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, "Could not setup port forwarding. Please make sure we can make an outbound connection to port 2010.");
            Logger.getLogger(SSHTunnel.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * @return the authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }
}
