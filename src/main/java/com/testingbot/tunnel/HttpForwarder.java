package com.testingbot.tunnel;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.util.Timeout;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import com.testingbot.tunnel.proxy.ForwarderHandler;

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

        // Own statistics for this listener: a wedged Selenium relay and ordinary proxy load
        // look identical without separating them.
        ConnectionStatistics seleniumStats = new ConnectionStatistics();
        connector.addBean(seleniumStats);
        TunnelMetrics.connectionMetrics().add(ConnectionMetrics.SELENIUM, seleniumStats);

        httpProxy.setStopAtShutdown(true);

        httpProxy.addConnector(connector);

        // Selenium traffic goes to the hub through the SSH tunnel, never through the
        // upstream --proxy: the target is 127.0.0.1. The old servlet was handed "proxy"
        // and "proxyAuth" init parameters that AbstractProxyServlet never read, so they
        // had no effect; they are dropped rather than reimplemented.
        httpProxy.setHandler(new ForwarderHandler(app));

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
        // Give the SSH tunnel a moment to fully establish
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        RequestConfig cfg = RequestConfig.custom()
            .setConnectTimeout(Timeout.of(5, TimeUnit.SECONDS))
            .setResponseTimeout(Timeout.of(10, TimeUnit.SECONDS))
            .setRedirectsEnabled(false)
            .build();

        try (CloseableHttpClient client = HttpClients.custom()
            .setDefaultRequestConfig(cfg)
            .build()) {
            HttpHead req = new HttpHead("http://127.0.0.1:" + seleniumPort + "/");
            return client.execute(req, response -> response.getCode() == HttpStatus.SC_OK);
        } catch (Exception ex) {
            Logger.getLogger(HttpForwarder.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
    }
}
