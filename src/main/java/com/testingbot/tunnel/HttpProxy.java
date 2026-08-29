package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.CustomConnectHandler;
import com.testingbot.tunnel.proxy.CustomDnsResolver;
import com.testingbot.tunnel.proxy.TunnelProxyHandler;
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
    /** Re-armed on every start(); Jetty resets the connector statistics it reads. */
    private java.util.function.LongSupplier bytesSupplier;

    /** Relay buffer size; Jetty's default is 4 KiB, which is small for bulk tunnelling. */
    static final int TUNNEL_BUFFER_SIZE = 32 * 1024;
    /** Relay idle timeout; must outlast quiet periods on an otherwise live tunnel. */
    static final long TUNNEL_IDLE_TIMEOUT_MS = 300_000L;
    /** How long shutdown waits for in-flight requests to finish. */
    static final long STOP_TIMEOUT_MS = 5_000L;
    /** Idle timeout for the proxy's outbound HTTP client. */
    static final long PROXY_IDLE_TIMEOUT_MS = 120_000L;
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
        // resets when the connector restarts on an SSH reconnect. Reporting it directly made
        // the status endpoint drop to zero on every reconnect, so stop() folds the connector's
        // figure into an accumulated total and start() re-arms the supplier against the fresh
        // statistics. Note the total still trails live traffic by whatever is still open.
        this.bytesSupplier = connectionMetrics::totalBytes;
        Statistics.setBytesTransferredSupplier(bytesSupplier);

        httpProxy.addConnector(proxyConnector);
        httpProxy.setStopAtShutdown(true);
        // Give in-flight requests a chance to finish on shutdown instead of having their
        // connections cut mid-response.
        httpProxy.setStopTimeout(STOP_TIMEOUT_MS);

        // One resolver shared by all three dial paths, so --dns applies to plain HTTP,
        // HTTPS CONNECT and the WebSocket relay alike. Null when --dns was not given.
        CustomDnsResolver dnsResolver = CustomDnsResolver.create(app.getDnsServer());

        ConnectHandler connectHandler = new CustomConnectHandler(app);
        ((CustomConnectHandler) connectHandler).setBlackList(app.getFastFail());
        ((CustomConnectHandler) connectHandler).setDnsResolver(dnsResolver);
        tuneTunnelRelay(connectHandler);

        WebsocketHandler websocketHandler = new WebsocketHandler();
        websocketHandler.setDnsResolver(dnsResolver);
        tuneTunnelRelay(websocketHandler);

        TunnelProxyHandler proxyHandler = new TunnelProxyHandler();
        proxyHandler.setIdleTimeoutMs(PROXY_IDLE_TIMEOUT_MS);
        proxyHandler.setBlackList(app.getFastFail());
        proxyHandler.setExtraHeaders(app.getCustomHeaders());
        proxyHandler.setDebugMode(app.isDebugMode());
        proxyHandler.setUpstreamProxy(app.getProxy(), app.getProxyAuth());
        proxyHandler.setBasicAuth(app.getBasicAuth());
        proxyHandler.setDnsResolver(dnsResolver);

        connectHandler.setHandler(proxyHandler);
        websocketHandler.setHandler(connectHandler);

        // GracefulHandler tracks in-flight requests so stop() can drain them; it must sit
        // outermost to see every request.
        GracefulHandler gracefulHandler = new GracefulHandler();
        gracefulHandler.setHandler(websocketHandler);

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

        start();

        shutDownHook = new Thread(new ShutDownHook(httpProxy));

        Runtime.getRuntime().addShutdownHook(shutDownHook);
    }

    /**
     * Both handlers relay bulk traffic, so Jetty's defaults (4 KiB buffers, 30s idle) are
     * conservative: larger buffers mean fewer syscalls per megabyte, and a longer idle
     * timeout keeps quiet-but-live tunnels (an open WebSocket, a paused download) from
     * being torn down under the client.
     */
    private static void tuneTunnelRelay(ConnectHandler handler) {
        handler.setBufferSize(TUNNEL_BUFFER_SIZE);
        handler.setIdleTimeout(TUNNEL_IDLE_TIMEOUT_MS);
    }

    public void stop() {
        Runtime.getRuntime().removeShutdownHook(shutDownHook);

        try {
            httpProxy.stop();
        } catch (Exception ex) {
            Logger.getLogger(HttpProxy.class.getName()).log(Level.SEVERE, null, ex);
        }
        // After stop(), so connections closed during the graceful shutdown are included.
        Statistics.carryBytesTransferred();
    }

    public void start() {
        try {
            httpProxy.start();
            // Jetty resets ConnectionStatistics in doStart(), and stop() detached the supplier
            // once its value was banked. Without re-arming here the reported byte total froze
            // permanently at the first SSH reconnect -- worse than the reset it replaced.
            Statistics.setBytesTransferredSupplier(bytesSupplier);
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
