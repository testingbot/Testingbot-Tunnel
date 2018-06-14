/*
 * Copyright testingbot.
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

/**
 *
 * @author testingbot
 */
public final class Doctor {

    private final App app;
    public Doctor(App app) {
        this.app = app;
        app.setFreeJettyPort();
        ArrayList<URI> uris = new ArrayList<>();
        try {
            uris.add(new URI("https://testingbot.com"));
            uris.add(new URI("https://api.testingbot.com/v1/browsers"));
            uris.add(new URI("https://www.google.com/"));
        } catch (URISyntaxException e) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, e.getMessage());
        }
        
        performChecks(uris);
    }
    
    public void performChecks(ArrayList<URI> uris) {
        try {
            for (URI uri : uris) {
                performCheck(uri);
            }
        } catch (UnknownHostException e) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, e.getMessage());
        }
        
        checkOpenPorts();
    }
    
    public void checkOpenPorts() {
        // check jetty port
        boolean canOpenJetty = checkPortOpen(app.getJettyPort());
        if (!canOpenJetty) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Cannot open proxy port {0}", Integer.toString(app.getJettyPort()));
        } else {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "OK - Proxy port {0} can be opened", Integer.toString(app.getJettyPort()));
        }
        
        boolean canOpenSEPort = checkPortOpen(app.getSeleniumPort());
        if (!canOpenSEPort) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, "Cannot open Selenium port {0}", Integer.toString(app.getSeleniumPort()));
        } else {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "OK - Selenium port {0} can be opened", Integer.toString(app.getJettyPort()));
        }
    }
    
    private boolean checkPortOpen(int port) {
        try {
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.getLocalPort();
            }
            return true;
        } catch (IOException ex) {
        }
        
        return false;
    }
    
    public void performCheck(final URI uri) throws UnknownHostException {
        Logger.getLogger(App.class.getName()).log(Level.INFO, "Resolving {0}", new Object[]{ uri.toString() });
        InetAddress address = InetAddress.getByName(uri.getHost()); 
        Logger.getLogger(App.class.getName()).log(Level.INFO, "OK - Resolved {0} to {1}", new Object[]{ uri.toString(), address.getHostAddress() });
        if (checkConnection(uri)) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "OK - URL {0} can be reached.", uri.toString());
        } else {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, "URL {0} can not be reached.", uri.toString());
        }
    }
    
    private boolean checkConnection(final URI uri) {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpGet getRequest = new HttpGet(uri);
        
        HttpResponse response;
        try {
            response = httpClient.execute(getRequest);
        } catch (IOException ex) {
            return false;
        }

        return (response.getStatusLine().getStatusCode() == 200);
    }
}