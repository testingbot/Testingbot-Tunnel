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
        public void run() {
            Api api = app.getApi();
            JSONObject response;
            try {
                response = api.pollTunnel(tunnelID);
            
                if (response.getString("state").equals("READY")) {
                   timer.cancel();
                   app.tunnelReady(response);
                } else {
                    Logger.getLogger(TunnelPoller.class.getName()).log(Level.INFO, "Current tunnel status: {0}", response.getString("state"));
                }
            } catch (Exception ex) {
                Logger.getLogger(TunnelPoller.class.getName()).log(Level.SEVERE, "Unable to poll for tunnel status.");
            }
        }
    }
}
