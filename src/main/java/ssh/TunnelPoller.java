package ssh;

import com.testingbot.tunnel.Api;
import com.testingbot.tunnel.App;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;

/**
 *
 * @author TestingBot
 */
public class TunnelPoller {
    private final App app;
    private final Timer timer;
    private final String tunnelID;
    private int pollerCount = 0;
    
    public TunnelPoller(App app, String tunnelID) {
        this.app = app;
        this.tunnelID = tunnelID;
        timer = new Timer();
        timer.schedule(new PollTask(), 5000, 5000);
    }
    
    public void cancel() {
        timer.cancel();
    }
    
    class PollTask extends TimerTask {
        int counter = 0;
        
        @Override
        public void run() {
            Api api = app.getApi();
            JSONObject response;
            try {
                response = api.pollTunnel(tunnelID);
                
                if (this.counter > 80) {
                    Logger.getLogger(TunnelPoller.class.getName()).log(Level.SEVERE, "Unable to create tunnel, waited for 400 seconds. Please try again or check https://status.testingbot.com");
                    timer.cancel();
                }
            
                if (response.getString("state").equals("READY")) {
                   timer.cancel();
                   app.tunnelReady(response);
                } else {
                    this.counter += 1;
                    Logger.getLogger(TunnelPoller.class.getName()).log(Level.INFO, "Current tunnel status: {0}", response.getString("state"));
                }
            } catch (Exception ex) {
                timer.cancel();
                Logger.getLogger(TunnelPoller.class.getName()).log(Level.SEVERE, "Unable to poll for tunnel status.");
            }
        }
    }
}
