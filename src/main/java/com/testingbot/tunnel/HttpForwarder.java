package com.testingbot.tunnel;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHolder;

import com.testingbot.tunnel.proxy.ForwarderServlet;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
/**
 *
 * @author TestingBot
 */
public class HttpForwarder {
    private App app;
    private final Server httpProxy;
    
    public HttpForwarder(App app) {
        this.app = app;
        httpProxy = new Server();
        HttpConfiguration http_config = new HttpConfiguration();
        ServerConnector connector = new ServerConnector(httpProxy,
                new HttpConnectionFactory(http_config));
        connector.setPort(app.getSeleniumPort());
        connector.setIdleTimeout(440000);
        
        httpProxy.setStopAtShutdown(true);
        
        httpProxy.addConnector(connector);
        
        ServletHolder servletHolder = new ServletHolder(new ForwarderServlet(app));
        servletHolder.setInitParameter("idleTimeout", "440000");
        servletHolder.setInitParameter("timeout", "440000");
        if (app.getProxy() != null) {
            servletHolder.setInitParameter("proxy", app.getProxy());     
        }
        
        if (app.getProxyAuth()!= null) {
            servletHolder.setInitParameter("proxyAuth", app.getProxyAuth());     
        }
        
        ServletContextHandler ctxHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctxHandler.setContextPath("/");
        ctxHandler.addServlet(servletHolder, "/*");
        
        httpProxy.setHandler(ctxHandler);
        
        try {
            httpProxy.start();
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.WARNING, "Could not set up local forwarder. Please make sure this program can open port 4445 on this computer.");
        }
    }
    
    public void stop() {
        try {
            httpProxy.stop();
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public boolean testForwarding() {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpGet getRequest = new HttpGet("http://127.0.0.1:" + app.getSeleniumPort());
        
        HttpResponse response;
        try {
            response = httpClient.execute(getRequest);
        } catch (IOException ex) {
            return false;
        }

        return (response.getStatusLine().getStatusCode() == 200);
    }
}

