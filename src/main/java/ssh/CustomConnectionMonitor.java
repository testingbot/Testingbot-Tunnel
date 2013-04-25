package ssh;

import ch.ethz.ssh2.ConnectionMonitor;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 *
 * @author TestingBot
 */
public class CustomConnectionMonitor implements ConnectionMonitor {
    private SSHTunnel tunnel;
    private Timer timer;
    private boolean retrying = false;
    
    public CustomConnectionMonitor(SSHTunnel tunnel) {
        this.tunnel = tunnel;
    }
    
    public void connectionLost(Throwable reason) {
        if (tunnel.isShuttingDown() == true) {
            return;
        }
        
        Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.SEVERE, "SSH Connection lost! {0}", reason.getMessage());
        
        if (this.retrying == false) {
            this.retrying = true;
            timer = new Timer();
            timer.schedule(new PollTask(), 10000, 10000);
        }
    }
    
    class PollTask extends TimerTask {
        public void run() {
            try {

                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO, "Trying to re-establish SSH Connection");
                tunnel.stop();
                tunnel.connect();
                if (tunnel.isAuthenticated()) {
                    retrying = false;
                    timer.cancel();
                    tunnel.createPortForwarding();
                    Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.INFO, "Successfully re-established SSH Connection");
                }
            } catch (Exception ex) {
                Logger.getLogger(CustomConnectionMonitor.class.getName()).log(Level.WARNING, ex.getMessage());
            }
        }
    }
}
