package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.TunnelProxyServlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
/**
 *
 * @author TestingBot
 */
public class HttpProxy {
    private App app;
    private Server httpProxy;
    private final int randomNumber = (int )(Math.random() * 50 + 1);
    
    public HttpProxy(App app) {
        this.app = app;
        
        this.httpProxy = new Server();
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

        start();

        Thread shutDownHook = new Thread(new ShutDownHook(httpProxy));

        Runtime.getRuntime().addShutdownHook(shutDownHook);
    }

    public void stop() {
        try {
            httpProxy.stop();
        } catch (Exception ex) {
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void start() {
        try {
            httpProxy.start();
        } catch (Exception ex) {
            Logger.getLogger(HttpProxy.class.getName()).log(Level.INFO, "Could not set up local http proxy. Please make sure this program can open port 8087 on this computer.");
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private ServerSocket _findAvailableSocket() {
        int[] ports = {80, 888, 2000, 2001, 2020, 2222, 3000, 3001, 3030, 3333, 4000, 4001, 4040, 4502, 4503, 5000, 5001, 5050, 5555, 6000, 6001, 6060, 6666, 7000, 7070, 7777, 8000, 8001, 8080, 8888, 9000, 9001, 9090, 9999};
        
        for (int port : ports) {
            try {
                return new ServerSocket(port);
            } catch (IOException ex) {
            }
        }
        
        return null;
    }
    
    public boolean testProxy() {
        // find a free port, create a webserver, make a request to the proxy endpoint, expect it to arrive here.
        
        ServerSocket serverSocket;
        int port;
        try {
            serverSocket = _findAvailableSocket();
            if (serverSocket == null) {
                return true;
            }
            
            port = serverSocket.getLocalPort();
            serverSocket.close();
        } catch (IOException ex) {
            // no port available? assume everything is ok
            return true;
        }
        
        Server server = new Server(port);
        server.setHandler(new TestHandler());
        try {
            server.start();
        } catch (Exception e) {
            return true;
        }
        
        try {
            DefaultHttpClient httpClient = new DefaultHttpClient();
            String url = "https://api.testingbot.com/v1/tunnel/test";
            HttpPost postRequest = new HttpPost(url);
            
            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
            nameValuePairs.add(new BasicNameValuePair("client_key", app.getClientKey()));
            nameValuePairs.add(new BasicNameValuePair("client_secret", app.getClientSecret()));
            nameValuePairs.add(new BasicNameValuePair("tunnel_id", Integer.toString(app.getTunnelID())));
            nameValuePairs.add(new BasicNameValuePair("test_port", Integer.toString(port)));
            
            postRequest.setEntity(new UrlEncodedFormEntity(nameValuePairs));
            
            HttpResponse response = httpClient.execute(postRequest);
            BufferedReader br = new BufferedReader(
                     new InputStreamReader((response.getEntity().getContent()), "UTF8"));

            String output;
            StringBuilder sb = new StringBuilder();
            while ((output = br.readLine()) != null) {
                    sb.append(output);
            }
            try {
                server.stop();
            } catch (Exception ex) {
                
            }
            
            return ((response.getStatusLine().getStatusCode() == 201) && (sb.indexOf("test=" + this.randomNumber) > -1));
        } catch (IOException ex) {
            return true;
        }
    }
    
    private class TestHandler extends AbstractHandler {
        public void handle(String target,
                       Request baseRequest,
                       HttpServletRequest request,
                       HttpServletResponse response) 
        throws IOException, ServletException
        {
            response.setContentType("text/html;charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            response.getWriter().println("test=" + Integer.toString(randomNumber));
        }
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

