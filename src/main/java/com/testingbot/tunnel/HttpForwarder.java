package com.testingbot.tunnel;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import com.testingbot.tunnel.proxy.ForwarderServlet;
/**
 *
 * @author jochen
 */
public class HttpForwarder {
    
    public HttpForwarder(App app) {
        Server httpProxy = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(Integer.parseInt(app.getSeleniumPort()));
        connector.setMaxIdleTime(400000);
        connector.setThreadPool(new QueuedThreadPool(128));
        
        httpProxy.setGracefulShutdown(3000);
        httpProxy.setStopAtShutdown(true);
        
        httpProxy.addConnector(connector);
        ServletHandler servletHandler = new ServletHandler();
        servletHandler.addServletWithMapping(new ServletHolder(new ForwarderServlet(app)), "/*");
        
        httpProxy.setHandler(servletHandler);
        try {
            httpProxy.start();
        } catch (Exception ex) {
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.INFO, "Could not set up local forwarder. Please make sure this program can open port 4445 on this computer.");
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

