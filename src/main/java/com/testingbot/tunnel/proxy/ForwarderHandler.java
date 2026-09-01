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

    /** Looked up once: this was a global map lookup on every Selenium request. */
    private static final Logger JUL = Logger.getLogger(ForwarderHandler.class.getName());


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

    /**
     * Same override as {@link TunnelProxyHandler}: ProxyHandler's default builds the outbound
     * request with {@code HttpURI.toURI()}, and {@code java.net.URI} rejects characters Jetty's
     * server parser accepts. Without it a Selenium request such as
     * {@code /wd/hub/status?caps={"browser":"chrome"}} 500s before reaching the hub -- the exact
     * class of input TB-314 fixed on the other proxy and missed on this one.
     */
    @Override
    protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest,
                                                                       HttpURI newHttpURI) {
        int port = newHttpURI.getPort();
        if (port <= 0) {
            port = HttpScheme.HTTPS.is(newHttpURI.getScheme()) ? 443 : 80;
        }
        String pathQuery = newHttpURI.getPathQuery();
        if (pathQuery == null || pathQuery.isEmpty()) {
            pathQuery = "/";
        }
        return getHttpClient().newRequest(newHttpURI.getHost(), port)
                .scheme(newHttpURI.getScheme())
                .path(pathQuery)
                // No Request.timeout() here either: it caps the whole conversation rather than
                // idle time, and a Selenium command can legitimately run long. The client's idle
                // timeout below bounds a stalled hub.
                .method(clientToProxyRequest.getMethod());
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

        // Until this honoured --log-http, every Selenium relay request produced an INFO line
        // whatever the user asked for -- including --log-http none. The proxy chain was fixed
        // for that in TB-319 and this path was missed, which is what per-module logging made
        // visible: --log-http forwarder:none had nothing to switch off.
        HttpLogHandler.Mode mode = app.getLogHttpPolicy().modeFor(LogHttpPolicy.FORWARDER);
        if (mode == HttpLogHandler.Mode.NONE || mode == HttpLogHandler.Mode.ERRORS) {
            // ERRORS logs nothing here; failures are reported by
            // onServerToProxyResponseFailure, which is where the outcome is known.
            return;
        }

        JUL.log(Level.INFO, "[{0}] {1}",
                new Object[]{clientToProxyRequest.getMethod(), clientToProxyRequest.getHttpURI()});

        if (mode == HttpLogHandler.Mode.HEADERS || app.isDebugMode()) {
            StringBuilder sb = new StringBuilder();
            for (HttpField field : clientToProxyRequest.getHeaders()) {
                sb.append(field.getName()).append(": ")
                        .append(SensitiveHeaders.redactValue(field.getName(), field.getValue()))
                        .append(System.lineSeparator());
            }
            JUL.log(Level.INFO, sb.toString());
        }
    }

    @Override
    protected void onServerToProxyResponseFailure(Request clientToProxyRequest,
                                                  org.eclipse.jetty.client.Request proxyToServerRequest,
                                                  org.eclipse.jetty.client.Response serverToProxyResponse,
                                                  org.eclipse.jetty.server.Response proxyToClientResponse,
                                                  org.eclipse.jetty.util.Callback proxyToClientCallback,
                                                  Throwable failure) {
        JUL.log(Level.WARNING,
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
        JUL.log(Level.WARNING,
                "Proxy response failure: {0}", failure.getMessage());
        super.onProxyToClientResponseFailure(clientToProxyRequest, proxyToServerRequest,
                serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
    }
}
