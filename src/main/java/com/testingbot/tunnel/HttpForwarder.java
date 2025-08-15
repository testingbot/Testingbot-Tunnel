package com.testingbot.tunnel;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHolder;

import com.testingbot.tunnel.proxy.ForwarderServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 *
 * @author TestingBot
 */
public class HttpForwarder {
    private final int seleniumPort;
    private final Server httpProxy;

    public HttpForwarder(App app) {
        this.seleniumPort = app.getSeleniumPort();
        httpProxy = new Server();
        ServerConnector connector = new ServerConnector(httpProxy);
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
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.SEVERE, "Could not set up local forwarder. Please make sure this program can open port {0} on this computer.\nPerhaps another tunnel process is already running on this machine?", Integer.toString(app.getSeleniumPort()));
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void stop() {
        try {
            httpProxy.stop();
        } catch (Exception ex) {
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public boolean testForwarding() {
        RequestConfig cfg = RequestConfig.custom()
            .setConnectTimeout(2000)
            .setSocketTimeout(2000)
            .setRedirectsEnabled(false)
            .build();

        try (CloseableHttpClient client = HttpClients.custom()
            .setDefaultRequestConfig(cfg)
            .build()) {
            HttpHead req = new HttpHead("http://127.0.0.1:" + seleniumPort + "/");
            try (CloseableHttpResponse resp = client.execute(req)) {
                return resp.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
            }
        } catch (Exception ex) {
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }
}

