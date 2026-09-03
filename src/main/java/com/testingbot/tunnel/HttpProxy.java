package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.ConnectToMap;
import com.testingbot.tunnel.proxy.HeaderRules;
import com.testingbot.tunnel.proxy.HttpLogHandler;
import com.testingbot.tunnel.proxy.LocalhostPolicy;
import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.CustomDnsResolver;
import com.testingbot.tunnel.proxy.TunnelProxyHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.testingbot.tunnel.proxy.ProxySpec;
import com.testingbot.tunnel.proxy.ProxyAuthenticator;
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
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.StateTrackingHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.VirtualThreads;
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
    private boolean shutdownHookRegistered;

    /** This connector's own statistics; Jetty resets them whenever it restarts. */
    private ConnectionStatistics proxyConnectionStats;

    /** Relay buffer size; Jetty's default is 4 KiB, which is small for bulk tunnelling. */
    static final int TUNNEL_BUFFER_SIZE = 32 * 1024;
    /** Relay idle timeout; must outlast quiet periods on an otherwise live tunnel. */
    static final long TUNNEL_IDLE_TIMEOUT_MS = 300_000L;
    /** How long shutdown waits for in-flight requests to finish. */
    static final long STOP_TIMEOUT_MS = 5_000L;
    /** Idle timeout for the proxy's outbound HTTP client. */
    static final long PROXY_IDLE_TIMEOUT_MS = 120_000L;

    /** Header allowance in both directions, matching the outbound client's buffer size. */
    static final int PROXY_HEADER_SIZE = 32 * 1024;
    /** Under --debug, how long a handler may hold its callback before being reported. */
    static final long HANDLER_CALLBACK_TIMEOUT_MS = 60_000L;

    /**
     * A tunnel is I/O-bound and holds many mostly-idle connections, which is exactly the
     * shape virtual threads suit. They only exist on Java 21+, and the supported baseline
     * is 17, so this is opportunistic: on an older JVM {@code VirtualThreads.areSupported()}
     * is false and Jetty's normal pooled threads are used unchanged.
     */
    private static QueuedThreadPool newThreadPool() {
        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setName("tb-tunnel");
        if (VirtualThreads.areSupported()) {
            pool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
            Logger.getLogger(HttpProxy.class.getName())
                .log(Level.INFO, "Using virtual threads for the local proxy");
        }
        return pool;
    }

    public HttpProxy(App app) {
        this.app = app;

        this.httpProxy = new Server(newThreadPool());

        HttpConfiguration http_config = new HttpConfiguration();
        // Don't advertise "Server: Jetty(<version>)" to every client; the exact version
        // is of no use to callers and only helps someone matching known CVEs.
        http_config.setSendServerVersion(false);
        // Match the outbound client's 32 KiB buffers. Jetty defaults both of these to 8 KiB, so
        // the proxy refused with 431 requests it was perfectly capable of forwarding -- long
        // session cookies and large bearer tokens clear 8 KiB routinely in enterprise setups,
        // and the client on the far side would have carried them.
        http_config.setRequestHeaderSize(PROXY_HEADER_SIZE);
        http_config.setResponseHeaderSize(PROXY_HEADER_SIZE);

        ServerConnector proxyConnector = new ServerConnector(httpProxy,
                new HttpConnectionFactory(http_config));

        proxyConnector.setPort(app.getJettyPort());
        proxyConnector.setIdleTimeout(400000);

        // Jetty counts bytes and connections on the connector itself, so tunnelled
        // traffic (CONNECT, WebSocket) is included -- unlike the per-request counting
        // in the proxy servlet, which only ever saw plain HTTP.
        ConnectionStatistics connectionStats = new ConnectionStatistics();
        proxyConnector.addBean(connectionStats);
        proxyConnector.addBean(new org.eclipse.jetty.io.Connection.Listener() {
            @Override
            public void onOpened(org.eclipse.jetty.io.Connection connection) {
                TunnelMetrics.ACTIVE_CONNECTIONS.inc();
            }

            @Override
            public void onClosed(org.eclipse.jetty.io.Connection connection) {
                TunnelMetrics.ACTIVE_CONNECTIONS.dec();
            }
        });
        ConnectionMetrics connectionMetrics = TunnelMetrics.connectionMetrics();
        connectionMetrics.add(ConnectionMetrics.PROXY, connectionStats);
        // Jetty's ConnectionStatistics only records bytes when a connection CLOSES, and it
        // resets when this connector restarts on an SSH reconnect. Reporting it directly made
        // the status endpoint drop to zero on every reconnect, so stop() banks this connector's
        // figure and the supplier keeps reading the live registry on top of it. Note the total
        // still trails live traffic by whatever is still open.
        this.proxyConnectionStats = connectionStats;
        Statistics.setBytesTransferredSupplier(connectionMetrics::totalBytes);

        httpProxy.addConnector(proxyConnector);
        httpProxy.setStopAtShutdown(true);
        // Give in-flight requests a chance to finish on shutdown instead of having their
        // connections cut mid-response.
        httpProxy.setStopTimeout(STOP_TIMEOUT_MS);

        // One resolver shared by all three dial paths, so --dns applies to plain HTTP,
        // HTTPS CONNECT and the WebSocket relay alike. Null when --dns was not given.
        CustomDnsResolver dnsResolver = CustomDnsResolver.create(
                app.getDnsServer(), app.getDnsTimeout(), app.isDnsRoundRobin());
        // Likewise shared, so a --connect-to rule means the same thing on every path.
        ConnectToMap connectTo = ConnectToMap.parse(app.getConnectTo());
        LocalhostPolicy localhostPolicy = LocalhostPolicy.parse(app.getLocalhostPolicy());

        ConnectHandler connectHandler = new CustomConnectHandler(app);
        ((CustomConnectHandler) connectHandler).setAllowedHosts(app.getAllowedHosts());
        ((CustomConnectHandler) connectHandler).setBlackList(app.getFastFail());
        ((CustomConnectHandler) connectHandler).setDnsResolver(dnsResolver);
        ((CustomConnectHandler) connectHandler).setConnectTo(connectTo);
        ((CustomConnectHandler) connectHandler).setLocalhostPolicy(localhostPolicy);
        ((CustomConnectHandler) connectHandler).setPacPolicy(app.getPacPolicy());
        // Never set, so --debug dumped no CONNECT headers and never logged the line saying the
        // upstream-proxy handshake had completed -- on the path where that is the thing being
        // debugged. The plain-HTTP handler below has always had it.
        ((CustomConnectHandler) connectHandler).setDebugMode(app.isDebugMode());
        tuneTunnelRelay(connectHandler);

        WebsocketHandler websocketHandler = new WebsocketHandler();
        websocketHandler.setDnsResolver(dnsResolver);
        websocketHandler.setConnectTo(connectTo);
        // The same destination policies the CONNECT and plain-HTTP handlers apply. Without
        // these a ws:// upgrade reached anything at all, whatever was configured.
        websocketHandler.setAllowedHosts(app.getAllowedHosts());
        websocketHandler.setBlackList(app.getFastFail());
        websocketHandler.setLocalhostPolicy(localhostPolicy);
        // And the same egress routing. A ws:// upgrade used to be dialled straight at the target
        // whatever --proxy said, so on a proxy-only network it was the one scheme that hung.
        ProxySpec websocketUpstream = ProxySpec.parse(app.getProxy());
        websocketHandler.setProxySpec(websocketUpstream);
        websocketHandler.setPacPolicy(app.getPacPolicy());
        websocketHandler.setProxyUserPassword(app.getProxyAuth());
        websocketHandler.setWsProxyMode(app.getWsProxyMode());
        // SOCKS5 authenticates inside its own handshake, so an HTTP authenticator is not applied
        // to it -- the rule CustomConnectHandler follows.
        websocketHandler.setProxyAuthenticator(
                websocketUpstream != null && websocketUpstream.isSocks5()
                        ? ProxyAuthenticator.none() : app.proxyAuthenticator());
        tuneTunnelRelay(websocketHandler);

        TunnelProxyHandler proxyHandler = new TunnelProxyHandler();
        proxyHandler.setIdleTimeoutMs(
                seconds(app.getHttpIdleTimeoutSeconds(), PROXY_IDLE_TIMEOUT_MS));
        if (app.getHttpDialTimeoutSeconds() != null) {
            proxyHandler.setConnectTimeoutMs(app.getHttpDialTimeoutSeconds() * 1000L);
        }
        proxyHandler.setAllowedHosts(app.getAllowedHosts());
        proxyHandler.setBlackList(app.getFastFail());
        proxyHandler.setExtraHeaders(app.getCustomHeaders());
        proxyHandler.setRequestHeaderRules(HeaderRules.parse(app.getHeaderRules()));
        proxyHandler.setResponseHeaderRules(HeaderRules.parse(app.getResponseHeaderRules()));
        proxyHandler.setDebugMode(app.isDebugMode());
        proxyHandler.setUpstreamProxy(app.getProxy(), app.getProxyAuth());
        proxyHandler.setProxyAuthenticator(app.proxyAuthenticator());
        proxyHandler.setNegotiateHosts(app.getNegotiateHosts(),
                app.getKrb5KeyTab() == null ? null : java.nio.file.Path.of(app.getKrb5KeyTab()),
                app.getKrb5Principal());
        proxyHandler.setKerberos(app.getProxySpn(),
                app.getKrb5KeyTab() == null ? null : java.nio.file.Path.of(app.getKrb5KeyTab()),
                app.getKrb5Principal());
        proxyHandler.setBasicAuth(app.getBasicAuth());
        proxyHandler.setDnsResolver(dnsResolver);
        proxyHandler.setConnectTo(connectTo);
        proxyHandler.setLocalhostPolicy(localhostPolicy);
        proxyHandler.setPacPolicy(app.getPacPolicy());

        connectHandler.setHandler(proxyHandler);
        websocketHandler.setHandler(connectHandler);

        // Outermost of the proxy handlers so plain HTTP, CONNECT and WebSocket upgrades all
        // get the same correlation id and the same --log-http switch.
        HttpLogHandler logHandler = new HttpLogHandler(
                app.getLogHttpPolicy().modeFor(
                        com.testingbot.tunnel.proxy.LogHttpPolicy.PROXY),
                app.getRequestIdHeader());
        logHandler.setHandler(websocketHandler);
        proxyHandler.setRequestIdHeader(logHandler.getRequestIdHeader());

        // GracefulHandler tracks in-flight requests so stop() can drain them; it must sit
        // outermost to see every request.
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(logHandler);

        if (app.isDebugMode()) {
            // Jetty 12 handlers own a Callback that must be completed exactly once. This
            // codebase implements several by hand, and getting that wrong shows up as a
            // hang rather than an exception. StateTrackingHandler reports the misuse
            // instead, so it is worth the overhead when debugging.
            StateTrackingHandler stateTracking = new StateTrackingHandler();
            stateTracking.setHandlerCallbackTimeout(HANDLER_CALLBACK_TIMEOUT_MS);
            stateTracking.setHandler(gracefulHandler);
            httpProxy.setHandler(stateTracking);
        } else {
            httpProxy.setHandler(gracefulHandler);
        }

        // Built before start(), which now registers it: the field must exist by then.
        shutDownHook = new Thread(new ShutDownHook(httpProxy));

        start();
    }

    /**
     * Both handlers relay bulk traffic, so Jetty's defaults (4 KiB buffers, 30s idle) are
     * conservative: larger buffers mean fewer syscalls per megabyte, and a longer idle
     * timeout keeps quiet-but-live tunnels (an open WebSocket, a paused download) from
     * being torn down under the client.
     */
    private void tuneTunnelRelay(ConnectHandler handler) {
        handler.setBufferSize(TUNNEL_BUFFER_SIZE);
        handler.setIdleTimeout(seconds(app.getHttpIdleTimeoutSeconds(), TUNNEL_IDLE_TIMEOUT_MS));
        Integer dial = app.getHttpDialTimeoutSeconds();
        if (dial != null) {
            handler.setConnectTimeout(dial * 1000L);
        }
    }

    /** The configured seconds as milliseconds, or {@code fallback} when nothing was configured. */
    private static long seconds(Integer configured, long fallback) {
        return configured == null ? fallback : configured * 1000L;
    }

    public void stop() {
        removeShutdownHook();

        try {
            httpProxy.stop();
        } catch (java.util.concurrent.TimeoutException drainTimedOut) {
            // GracefulHandler could not drain within STOP_TIMEOUT_MS, so Jetty closed the
            // remaining connections. On a busy tunnel that is a routine outcome of a reconnect;
            // logging it at SEVERE with a null message made every reconnect look like a crash.
            Logger.getLogger(HttpProxy.class.getName()).log(Level.INFO,
                "Local proxy did not finish in-flight requests within {0}ms; "
                + "remaining connections were closed.", STOP_TIMEOUT_MS);
        } catch (Exception ex) {
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE,
                "Could not stop the local proxy cleanly", ex);
        }
        // No banking here any more. ConnectionMetrics notices the reset Jetty performs in
        // doStart() and carries the previous total itself, which is both simpler and free of the
        // race this had: banking and resetting were two steps, and a scrape landing between them
        // counted the same bytes twice.
    }

    public void start() {
        try {
            httpProxy.start();
            // Re-armed on every start. stop() removes it, so without this the proxy lost its
            // shutdown hook at the first SSH reconnect and was never drained on JVM exit again.
            addShutdownHook();
        } catch (Exception ex) {
            throw new HttpProxyStartException(
                "Could not set up local http proxy. Please make sure this program can open port "
                        + app.getJettyPort() + " on this computer.", ex);
        }
    }

    private void addShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(shutDownHook);
            shutdownHookRegistered = true;
        } catch (IllegalStateException alreadyShuttingDown) {
            // The JVM is on its way down; there is nothing left to register for.
        }
    }

    private void removeShutdownHook() {
        if (!shutdownHookRegistered) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutDownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // Shutdown is already running and will invoke the hook itself.
        }
        shutdownHookRegistered = false;
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
            server.setHandler(new TestHandler(randomNumber));
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

    /**
     * Serves the body that {@link #testProxy()} asks TestingBot to fetch back through the
     * tunnel. Static and package-private so it can be tested without standing up a tunnel.
     */
    static class TestHandler extends Handler.Abstract {
        private final int randomNumber;

        TestHandler(int randomNumber) {
            this.randomNumber = randomNumber;
        }

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
