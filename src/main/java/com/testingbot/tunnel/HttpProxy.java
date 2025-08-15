package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.TunnelProxyServlet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.testingbot.tunnel.proxy.WebsocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
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
import org.eclipse.jetty.util.thread.QueuedThreadPool;

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

    public boolean testProxy() {
        Server server = null;
        try {
            // Start Jetty on loopback, ephemeral port
            server = new Server();
            ServerConnector connector = new ServerConnector(server, 1, 1);
            connector.setHost("127.0.0.1");
            connector.setPort(0);                 // let OS pick a free port
            connector.setIdleTimeout(10_000);
            server.addConnector(connector);
            server.setHandler(new TestHandler());
            server.start();

            int port = connector.getLocalPort();  // actual bound port

            // HttpClient with sane timeouts
            RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setSocketTimeout(5000)
                .setRedirectsEnabled(false)
                .build();

            try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(cfg)
                .build()) {

                HttpPost post = new HttpPost("https://api.testingbot.com/v1/tunnel/test");
                List<NameValuePair> form = Arrays.asList(
                    new BasicNameValuePair("client_key",    app.getClientKey()),
                    new BasicNameValuePair("client_secret", app.getClientSecret()),
                    new BasicNameValuePair("tunnel_id",     Integer.toString(app.getTunnelID())),
                    new BasicNameValuePair("test_port",     Integer.toString(port))
                );
                post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));

                try (CloseableHttpResponse resp = http.execute(post)) {
                    int status = resp.getStatusLine().getStatusCode();
                    String body = resp.getEntity() != null
                        ? EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8)
                        : "";
                    // Expect TB to echo what your local handler served
                    return status == 201 && body.contains("test=" + randomNumber);
                }
            }
        } catch (Exception e) {
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, e.getMessage());
            return false; // make failures explicit
        } finally {
            if (server != null) {
                try { server.stop(); } catch (Exception ignore) {}
                server.destroy();
            }
        }
    }

    private class TestHandler extends AbstractHandler {
        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/plain;charset=UTF-8");
            baseRequest.setHandled(true);
            response.getWriter().append("test=").append(String.valueOf(randomNumber));
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

