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
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

/**
 *
 * @author testingbot
 */
public final class Doctor {
    private final App app;
    private boolean hasFailures = false;

    public Doctor(App app) {
        this.app = app;
        app.setFreeJettyPort();
        ArrayList<URI> uris = new ArrayList<>();
        try {
            uris.add(new URI("https://testingbot.com"));
            uris.add(new URI("http://hub.testingbot.com"));
            uris.add(new URI("https://api.testingbot.com/v1/browsers"));
            uris.add(new URI("https://www.google.com/"));
        } catch (URISyntaxException e) {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, e.getMessage());
            hasFailures = true;
        }

        performChecks(uris);
    }

    public boolean hasFailures() {
        return hasFailures;
    }

    public void performChecks(ArrayList<URI> uris) {
        try {
            for (URI uri : uris) {
                performCheck(uri);
            }
        } catch (UnknownHostException e) {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, e.getMessage());
            hasFailures = true;
        }

        checkOpenPorts();
    }

    public void checkOpenPorts() {
        // check jetty port
        boolean canOpenJetty = checkPortOpen(app.getJettyPort());
        if (!canOpenJetty) {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, "Cannot open proxy port {0}.\nDoes this process have the correct permissions?", Integer.toString(app.getJettyPort()));
            hasFailures = true;
        } else {
            Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "OK - Proxy port {0} can be opened", Integer.toString(app.getJettyPort()));
        }

        boolean canOpenSEPort = checkPortOpen(app.getSeleniumPort());
        if (!canOpenSEPort) {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, "Cannot open Selenium port {0}.\nPerhaps another process is using this port? Or this process does not have the correct permission?", Integer.toString(app.getSeleniumPort()));
            hasFailures = true;
        } else {
            Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "OK - Selenium port {0} can be opened", Integer.toString(app.getSeleniumPort()));
        }
    }

    private boolean checkPortOpen(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void performCheck(final URI uri) throws UnknownHostException {
        Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "Resolving {0}", new Object[]{ uri.toString() });
        InetAddress address = InetAddress.getByName(uri.getHost());
        Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "OK - Resolved {0} to {1}", new Object[]{ uri.toString(), address.getHostAddress() });
        if (checkConnection(uri)) {
            Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "OK - URL {0} can be reached.", uri.toString());
        } else {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, "URL {0} can not be reached.", uri.toString());
            hasFailures = true;
        }
    }

    private boolean checkConnection(final URI uri) {
        RequestConfig cfg = RequestConfig.custom()
            .setConnectTimeout(Timeout.of(2, TimeUnit.SECONDS))
            .setResponseTimeout(Timeout.of(3, TimeUnit.SECONDS))
            .build();

        try (CloseableHttpClient client = HttpClients.custom()
            .setDefaultRequestConfig(cfg).build()) {
            HttpHead head = new HttpHead(uri);
            return client.execute(head, response -> {
                EntityUtils.consumeQuietly(response.getEntity());
                int sc = response.getCode();
                return sc >= 200 && sc < 400;
            });
        } catch (Exception e) {
            return false;
        }
    }
}
