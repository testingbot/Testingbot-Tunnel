package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.TunnelProxyServlet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
/**
 *
 * @author jochen
 */
public class HttpProxy {
    
    public HttpProxy(App app) {
        Server httpProxy = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(8087);
        connector.setMaxIdleTime(400000);
        connector.setThreadPool(new QueuedThreadPool(256));
        httpProxy.addConnector(connector);
        
        httpProxy.setGracefulShutdown(3000);
        httpProxy.setStopAtShutdown(true);
        
        HandlerCollection handlers = new HandlerCollection();
        httpProxy.setHandler(handlers);
        
        // Setup proxy servlet
        ServletContextHandler context = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
        ServletHolder proxyServlet = new ServletHolder(TunnelProxyServlet.class);
        if (app.getFastFail() != null && app.getFastFail().length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String domain : app.getFastFail()) {
                sb.append(domain).append(",");
            }
            proxyServlet.setInitParameter("blackList", sb.toString());
        }
        context.addServlet(proxyServlet, "/*");
        
        // Setup proxy handler to handle CONNECT methods
        ConnectHandler proxy = new CustomConnectHandler();
        if (app.getFastFail() != null && app.getFastFail().length > 0) {
            for (String domain : app.getFastFail()) {
                proxy.addBlack(domain);
            }
        }
        handlers.addHandler(proxy);

        try {
            httpProxy.start();
        } catch (Exception ex) {
            Logger.getLogger(HttpProxy.class.getName()).log(Level.INFO, "Could not set up local http proxy. Please make sure this program can open port 8087 on this computer.");
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, null, ex);
        }

        Thread shutDownHook = new Thread(new ShutDownHook(httpProxy));

        Runtime.getRuntime().addShutdownHook(shutDownHook);
    }
    
    private class ShutDownHook implements Runnable {
        private final Server proxy;

        ShutDownHook(Server proxy) {
          this.proxy = proxy;
        }

        public void run() {
            try {
                proxy.stop();
            } catch (Exception ex) {
                Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
      }
}

