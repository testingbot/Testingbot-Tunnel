package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.TunnelProxyServlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.testingbot.tunnel.proxy.WebsocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 *
 * @author TestingBot
 */
public final class HttpProxy {
    private final App app;
    private final Server httpProxy;
    private final int randomNumber = (int )(Math.random() * 50 + 1);
    private final Thread shutDownHook;

    public HttpProxy(App app) {
        this.app = app;

        this.httpProxy = new Server();

        HttpConfiguration http_config = new HttpConfiguration();

        ServerConnector proxyConnector = new ServerConnector(httpProxy,
                new HttpConnectionFactory(http_config));

        proxyConnector.setPort(app.getJettyPort());
        proxyConnector.setIdleTimeout(400000);
        httpProxy.addConnector(proxyConnector);
        httpProxy.setStopAtShutdown(true);

        ConnectHandler connectHandler = new CustomConnectHandler(app);
        WebsocketHandler websocketHandler = new WebsocketHandler();

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");  // Root path for all requests

        // AsyncProxyServlet for proxying HTTP requests
        ServletHolder proxyServlet = new ServletHolder(new TunnelProxyServlet());
        proxyServlet.setInitParameter("idleTimeout", "120000");
        proxyServlet.setInitParameter("timeout", "120000");

        if (app.getFastFail() != null && app.getFastFail().length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String domain : app.getFastFail()) {
                if (!domain.contains(":")) {
                    domain = domain + ":80"; // default port 80
                }
                sb.append(domain).append(",");
            }
            proxyServlet.setInitParameter("blackList", sb.toString());
        }

        if (app.isDebugMode()) {
            proxyServlet.setInitParameter("tb_debug", "true");
        }

        if (app.getProxy() != null) {
            proxyServlet.setInitParameter("proxy", app.getProxy());
        }

        if (app.getProxyAuth() != null) {
            proxyServlet.setInitParameter("proxyAuth", app.getProxyAuth());
        }

        if (app.getBasicAuth() != null) {
            proxyServlet.setInitParameter("basicAuth", String.join(",", app.getBasicAuth()));
        }

        proxyServlet.setInitParameter("jetty", String.valueOf(app.getJettyPort()));

        contextHandler.addServlet(proxyServlet, "/*");  // Proxy all HTTP requests

        // Add the context handler to the server
        HandlerList handlers = new HandlerList();
        handlers.addHandler(websocketHandler);  // For handling WS requests
        handlers.addHandler(connectHandler);  // For handling HTTPS requests (if needed)
        handlers.addHandler(contextHandler);  // For handling HTTP requests through proxy servlet
        httpProxy.setHandler(handlers);

        start();

        shutDownHook = new Thread(new ShutDownHook(httpProxy));

        Runtime.getRuntime().addShutdownHook(shutDownHook);
    }

    public void stop() {
        Runtime.getRuntime().removeShutdownHook(shutDownHook);

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
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, "Could not set up local http proxy. Please make sure this program can open port {0} on this computer.", Integer.toString(app.getJettyPort()));
            System.exit(1);
        }
    }

    private ServerSocket _findAvailableSocket() {
        int[] ports = {2000, 2001, 2020, 2222, 3000, 3001, 3030, 3333, 4000, 4001, 4040, 4502, 4503, 5000, 5001, 5050, 5555, 6000, 6001, 6060, 6666, 7000, 7070, 7777, 8000, 8001, 8080, 8888, 9000, 9001, 9090, 9999};

        for (int port : ports) {
            try {
                return new ServerSocket(port);
            } catch (IOException ignored) {
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
            HttpClient httpClient = HttpClientBuilder.create().build();
            String url = "https://api.testingbot.com/v1/tunnel/test";
            HttpPost postRequest = new HttpPost(url);

            List<NameValuePair> nameValuePairs = new ArrayList<>(2);
            nameValuePairs.add(new BasicNameValuePair("client_key", app.getClientKey()));
            nameValuePairs.add(new BasicNameValuePair("client_secret", app.getClientSecret()));
            nameValuePairs.add(new BasicNameValuePair("tunnel_id", Integer.toString(app.getTunnelID())));
            nameValuePairs.add(new BasicNameValuePair("test_port", Integer.toString(port)));

            postRequest.setEntity(new UrlEncodedFormEntity(nameValuePairs));

            HttpResponse response = httpClient.execute(postRequest);
            BufferedReader br = new BufferedReader(
                     new InputStreamReader((response.getEntity().getContent()), StandardCharsets.UTF_8));

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
        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
            response.setContentType("text/html;charset=utf-8");
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            response.getWriter().println("test=" + randomNumber);
        }
    }

    private static class ShutDownHook implements Runnable {
        private final Server proxy;

        ShutDownHook(Server proxy) {
          this.proxy = proxy;
        }

        @Override
        public void run() {
            try {
                proxy.stop();
            } catch (Exception ex) {
                Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}

