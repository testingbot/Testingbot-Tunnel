package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Request;

/**
 * Forwards Selenium traffic from the local {@code --se-port} to the TestingBot hub through
 * the SSH tunnel, tagging each request with the credentials and tunnel metadata the hub
 * needs to associate the session with this tunnel.
 *
 * <p>The SSH port is read per request rather than captured once: it changes when the tunnel
 * reconnects.
 */
public class ForwarderHandler extends ProxyHandler.Reverse {

    private static final long FORWARD_IDLE_TIMEOUT_MS = 440_000L;

    private final App app;

    public ForwarderHandler(App app) {
        super(request -> HttpURI.build()
                .scheme(HttpScheme.HTTP)
                .host("127.0.0.1")
                .port(app.getSSHPort())
                .path(request.getHttpURI().getPath())
                .query(request.getHttpURI().getQuery())
                .asImmutable());
        this.app = app;
    }

    @Override
    protected void configureHttpClient(HttpClient httpClient) {
        super.configureHttpClient(httpClient);
        // Selenium commands can block for a long time server-side (waits, page loads).
        httpClient.setIdleTimeout(FORWARD_IDLE_TIMEOUT_MS);
    }

    @Override
    protected void addProxyHeaders(Request clientToProxyRequest,
                                   org.eclipse.jetty.client.Request proxyToServerRequest) {
        super.addProxyHeaders(clientToProxyRequest, proxyToServerRequest);

        proxyToServerRequest.headers(fields -> {
            fields.put("TB-Tunnel", app.getServerIP());
            fields.put("TB-Tunnel-Version", App.VERSION.toString());
            fields.put("TB-Credentials", app.getClientKey() + "_" + app.getClientSecret());
            if (app.isBypassingSquid()) {
                fields.put("TB-Tunnel-Port", "2010");
            }
            if (app.getPac() != null) {
                fields.put("TB-Tunnel-Pac", app.getPac());
            }
        });

        Logger.getLogger(ForwarderHandler.class.getName()).log(Level.INFO, "[{0}] {1}",
                new Object[]{clientToProxyRequest.getMethod(), clientToProxyRequest.getHttpURI()});

        if (app.isDebugMode()) {
            StringBuilder sb = new StringBuilder();
            for (HttpField field : clientToProxyRequest.getHeaders()) {
                sb.append(field.getName()).append(": ")
                        .append(SensitiveHeaders.redactValue(field.getName(), field.getValue()))
                        .append(System.lineSeparator());
            }
            Logger.getLogger(ForwarderHandler.class.getName()).log(Level.INFO, sb.toString());
        }
    }

    @Override
    protected void onServerToProxyResponseFailure(Request clientToProxyRequest,
                                                  org.eclipse.jetty.client.Request proxyToServerRequest,
                                                  org.eclipse.jetty.client.Response serverToProxyResponse,
                                                  org.eclipse.jetty.server.Response proxyToClientResponse,
                                                  org.eclipse.jetty.util.Callback proxyToClientCallback,
                                                  Throwable failure) {
        Logger.getLogger(ForwarderHandler.class.getName()).log(Level.WARNING,
                "Error when forwarding request: {0}", failure.getMessage());
        super.onServerToProxyResponseFailure(clientToProxyRequest, proxyToServerRequest,
                serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
    }

    @Override
    protected void onProxyToClientResponseFailure(Request clientToProxyRequest,
                                                  org.eclipse.jetty.client.Request proxyToServerRequest,
                                                  org.eclipse.jetty.client.Response serverToProxyResponse,
                                                  org.eclipse.jetty.server.Response proxyToClientResponse,
                                                  org.eclipse.jetty.util.Callback proxyToClientCallback,
                                                  Throwable failure) {
        Logger.getLogger(ForwarderHandler.class.getName()).log(Level.WARNING,
                "Proxy response failure: {0}", failure.getMessage());
        super.onProxyToClientResponseFailure(clientToProxyRequest, proxyToServerRequest,
                serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
    }
}
