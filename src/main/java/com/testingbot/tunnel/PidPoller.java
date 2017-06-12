/*
 * Copyright TestingBot.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.testingbot.tunnel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import ssh.TunnelPoller;

/**
 *
 * @author testingbot
 */
public class PidPoller {
    private App app;
    private File pidFile;
    private Timer timer;
    private final Thread cleanupThread;
    
    public PidPoller(App app) {
        this.app = app;
        
        // create a "pid" file which we'll watch, when deleted, shutdown the tunnel
        final String fileName = "testingbot-tunnel.pid";
        
        cleanupThread = new Thread() {
          @Override
          public void run() {
              File f = new File(fileName);
              if (f.exists()) {
                    f.delete();
              }
          }
        };

        pidFile = new File(fileName);
        if (!pidFile.exists()) {
            try {
                FileWriter fw = new FileWriter(pidFile.getAbsoluteFile());
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write("TestingBot Tunnel, Remove this file to shutdown the tunnel");
                bw.close();
            } catch (IOException ex) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Can't create testingbot pidfile in current directory");
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
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
        public void run() {
            if (!pidFile.exists()) {
                Logger.getLogger(TunnelPoller.class.getName()).log(Level.INFO, "{0} pidFile was removed, shutting down Tunnel", pidFile.toString());
                timer.cancel();
                System.exit(0);
            }
        }
    }
}
