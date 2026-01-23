package com.testingbot.tunnel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author testingbot
 */
public class PidPoller {
    private final File pidFile;
    private Timer timer;
    private final Thread cleanupThread;

    public PidPoller(App app) {

        // create a "pid" file which we'll watch, when deleted, shutdown the tunnel
        String fileName = "testingbot-tunnel.pid";
        if (app.getTunnelIdentifier() != null && !app.getTunnelIdentifier().isEmpty()) {
            fileName = "testingbot-tunnel-" + app.getTunnelIdentifier() + ".pid";
        }

        final String pidFileName = fileName;

        cleanupThread = new Thread() {
          @Override
          public void run() {
              File f = new File(pidFileName);
              if (f.exists() && !f.delete()) {
                  Logger.getLogger(PidPoller.class.getName()).log(Level.WARNING, "Could not delete pid file: {0}", pidFileName);
              }
          }
        };

        pidFile = new File(fileName);
        if (!pidFile.exists()) {
            try (FileWriter fw = new FileWriter(pidFile.getAbsoluteFile());
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write("TestingBot Tunnel, Remove this file to shutdown the tunnel");
                Logger.getLogger(PidPoller.class.getName()).log(Level.INFO, "Pid file: {0}", pidFile.getAbsoluteFile().toString());
            } catch (IOException ex) {
                Logger.getLogger(PidPoller.class.getName()).log(Level.SEVERE, "Can't create testingbot pidfile in current directory", ex);
                return;
            }
        }

        Runtime.getRuntime().addShutdownHook(cleanupThread);

        timer = new Timer();
        timer.schedule(new PollTask(), 5000, 5000);
    }

    public void cancel() {
       Runtime.getRuntime().removeShutdownHook(cleanupThread);
       timer.cancel();
    }

    class PollTask extends TimerTask {
        @Override
        public void run() {
            if (!pidFile.exists()) {
                Logger.getLogger(PidPoller.class.getName()).log(Level.INFO, "{0} pidFile was removed, shutting down Tunnel", pidFile.toString());
                timer.cancel();
                System.exit(0);
            }
        }
    }
}
