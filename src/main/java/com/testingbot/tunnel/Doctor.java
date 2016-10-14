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
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import sun.net.dns.ResolverConfiguration;

/**
 *
 * @author testingbot
 */
public class Doctor {
    public Doctor() {
        ArrayList<URI> uris = new ArrayList<URI>();
        try {
            uris.add(new URI("https://testingbot.com"));
            uris.add(new URI("https://api.testingbot.com/v1/browsers"));
            uris.add(new URI("https://www.google.com/"));
        } catch (Exception e) {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, e.getMessage());
        }
        
        performChecks(uris);
    }
    
    public void performChecks(ArrayList<URI> uris) {
        try {
            for (URI uri : uris) {
                performCheck(uri);
            }
        } catch (Exception e) {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, e.getMessage());
        }
    }
    
    public void performCheck(final URI uri) throws UnknownHostException {
        String dnsServer = "";
        try {
            dnsServer = ResolverConfiguration.open().nameservers().get(0);
        } catch (Exception ignore) {}
        
        Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "Resolving {0} using DNS server {1}", new Object[]{ uri.toString(), dnsServer });
        InetAddress address = InetAddress.getByName(uri.getHost()); 
        Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "Resolved {0} to {1}", new Object[]{ uri.toString(), address.getHostAddress() });
        if (checkConnection(uri)) {
            Logger.getLogger(Doctor.class.getName()).log(Level.INFO, "URL {0} can be reached.", uri.toString());
        } else {
            Logger.getLogger(Doctor.class.getName()).log(Level.SEVERE, "URL {0} can not be reached.", uri.toString());
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
