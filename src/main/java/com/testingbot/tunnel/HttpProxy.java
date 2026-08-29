package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.TunnelProxyServlet;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.testingbot.tunnel.proxy.WebsocketHandler;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;

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
        proxyConnector.addBean(new Connection.Listener() {
            @Override
            public void onOpened(Connection connection) {
                TunnelMetrics.ACTIVE_CONNECTIONS.inc();
            }

            @Override
            public void onClosed(Connection connection) {
                TunnelMetrics.ACTIVE_CONNECTIONS.dec();
            }
        });
        httpProxy.addConnector(proxyConnector);
        httpProxy.setStopAtShutdown(true);

        ConnectHandler connectHandler = new CustomConnectHandler(app);
        ((CustomConnectHandler) connectHandler).setBlackList(app.getFastFail());
        WebsocketHandler websocketHandler = new WebsocketHandler();

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");  // Root path for all requests
        contextHandler.setAttribute("extra_headers", app.getCustomHeaders());

        // AsyncProxyServlet for proxying HTTP requests
        ServletHolder proxyServlet = new ServletHolder(new TunnelProxyServlet());
        proxyServlet.setInitParameter("idleTimeout", "120000");
        proxyServlet.setInitParameter("timeout", "120000");

        if (app.getFastFail() != null && app.getFastFail().length > 0) {
            proxyServlet.setInitParameter("blackList", String.join(",", app.getFastFail()));
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

        // In Jetty 12 both WebsocketHandler and ConnectHandler are Handler.Wrappers: each
        // inspects the request, takes it over if it owns it (WS upgrade / CONNECT), and
        // otherwise delegates to the handler it wraps. So the chain is nested rather than
        // a flat sequence, preserving the Jetty 11 order: WS -> CONNECT -> proxy servlet.
        connectHandler.setHandler(contextHandler);
        websocketHandler.setHandler(connectHandler);
        httpProxy.setHandler(websocketHandler);

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
            throw new HttpProxyStartException(
                "Could not set up local http proxy. Please make sure this program can open port "
                        + app.getJettyPort() + " on this computer.", ex);
        }
    }

    public static final class HttpProxyStartException extends RuntimeException {
        public HttpProxyStartException(String message, Throwable cause) {
            super(message, cause);
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
                .setConnectTimeout(Timeout.of(5, TimeUnit.SECONDS))
                .setResponseTimeout(Timeout.of(10, TimeUnit.SECONDS))
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

                return http.execute(post, response -> {
                    int status = response.getCode();
                    String body = response.getEntity() != null
                        ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                        : "";
                    // Expect TB to echo what your local handler served
                    return status == 201 && body.contains("test=" + randomNumber);
                });
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

    private class TestHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8");
            Content.Sink.write(response, true, "test=" + randomNumber, callback);
            return true;
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
