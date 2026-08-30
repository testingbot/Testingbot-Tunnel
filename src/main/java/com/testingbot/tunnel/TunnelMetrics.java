package com.testingbot.tunnel;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.hotspot.DefaultExports;

public final class TunnelMetrics {

    private TunnelMetrics() {
    }

    private static ConnectionMetrics connectionMetrics;

    /**
     * The process-wide connection metrics collector, created and registered on first use.
     *
     * <p>Shared rather than per-listener because Prometheus rejects duplicate registrations
     * of the same metric family, and the listeners come up at different times: the proxy and
     * Selenium forwarder when the tunnel starts, the metrics endpoint independently.
     */
    /**
     * Wraps a dial promise so the attempt is timed and counted whichever way it resolves.
     *
     * @param path which dial path this is: "proxy", "connect" or "websocket"
     */
    public static <T> org.eclipse.jetty.util.Promise<T> timedDial(
            String path, org.eclipse.jetty.util.Promise<T> delegate) {
        Histogram.Timer timer = DIAL_DURATION_SECONDS.labels(path).startTimer();
        return new org.eclipse.jetty.util.Promise<T>() {
            @Override
            public void succeeded(T result) {
                timer.observeDuration();
                DIAL_TOTAL.labels(path, "success").inc();
                delegate.succeeded(result);
            }

            @Override
            public void failed(Throwable x) {
                timer.observeDuration();
                DIAL_TOTAL.labels(path, "failure").inc();
                delegate.failed(x);
            }
        };
    }

    public static synchronized ConnectionMetrics connectionMetrics() {
        if (connectionMetrics == null) {
            connectionMetrics = new ConnectionMetrics();
            connectionMetrics.register();
        }
        return connectionMetrics;
    }

    public static final Counter HTTP_REQUESTS_TOTAL = Counter.build()
            .name("testingbot_http_requests_total")
            .help("Total HTTP requests proxied through the tunnel.")
            .labelNames("method", "code")
            .register();

    public static final Histogram HTTP_REQUEST_DURATION_SECONDS = Histogram.build()
            .name("testingbot_http_request_duration_seconds")
            .help("HTTP request duration as observed by the local proxy.")
            .labelNames("method")
            .register();

    public static final Histogram HTTP_REQUEST_SIZE_BYTES = Histogram.build()
            .name("testingbot_http_request_size_bytes")
            .help("HTTP request payload size in bytes (when known via Content-Length).")
            .labelNames("method")
            .exponentialBuckets(64, 4, 10)
            .register();

    public static final Histogram HTTP_RESPONSE_SIZE_BYTES = Histogram.build()
            .name("testingbot_http_response_size_bytes")
            .help("HTTP response payload size in bytes.")
            .labelNames("method")
            .exponentialBuckets(64, 4, 10)
            .register();

    public static final Gauge HTTP_REQUESTS_IN_FLIGHT = Gauge.build()
            .name("testingbot_http_requests_in_flight")
            .help("HTTP requests currently being proxied.")
            .labelNames("method")
            .register();

    public static final Counter HTTPS_CONNECT_TOTAL = Counter.build()
            .name("testingbot_https_connect_total")
            .help("Total HTTPS CONNECT requests handled.")
            .labelNames("code")
            .register();

    public static final Counter HTTPS_CONNECT_ERRORS_TOTAL = Counter.build()
            .name("testingbot_https_connect_errors_total")
            .help("HTTPS CONNECT failures by reason.")
            .labelNames("reason")
            .register();

    public static final Histogram HTTPS_CONNECT_DURATION_SECONDS = Histogram.build()
            .name("testingbot_https_connect_duration_seconds")
            .help("Duration of the HTTPS CONNECT handshake (time to establish the upstream tunnel).")
            .register();

    public static final Counter PROXY_BYTES_TRANSFERRED_TOTAL = Counter.build()
            .name("testingbot_proxy_bytes_transferred_total")
            .help("Total response bytes sent back to clients through the proxy.")
            .register();

    public static final Gauge TUNNEL_INFO = Gauge.build()
            .name("testingbot_tunnel_info")
            .help("Static information about the active tunnel. Value is 1.")
            .labelNames("version", "tunnel_id", "identifier")
            .register();

    public static final Gauge TUNNEL_UP = Gauge.build()
            .name("testingbot_tunnel_up")
            .help("1 if the SSH tunnel is currently connected, 0 otherwise.")
            .register();

    public static final Gauge TUNNEL_UPTIME_SECONDS = Gauge.build()
            .name("testingbot_tunnel_uptime_seconds")
            .help("Seconds since the tunnel process started.")
            .register();

    public static final Counter TUNNEL_RECONNECTS_TOTAL = Counter.build()
            .name("testingbot_tunnel_reconnects_total")
            .help("SSH tunnel reconnect attempts.")
            .register();

    public static final Counter TUNNEL_CONNECTS_TOTAL = Counter.build()
            .name("testingbot_tunnel_connects_total")
            .help("Successful SSH tunnel connects (initial + reconnects).")
            .register();

    public static final Gauge ACTIVE_CONNECTIONS = Gauge.build()
            .name("testingbot_active_connections")
            .help("Currently open client connections to the local proxy.")
            .register();

    public static final Histogram TUNNEL_CONNECT_DURATION_SECONDS = Histogram.build()
            .name("testingbot_tunnel_connect_duration_seconds")
            .help("Duration of the initial SSH connect handshake.")
            .register();

    /**
     * Proxy failures by classified reason. The label matches the X-TestingBot-Error header
     * on the response, so a dashboard spike and a header in a bug report name the same thing.
     * Complements the coarser ERRORS_TOTAL and HTTPS_CONNECT_ERRORS_TOTAL, which existing
     * dashboards depend on and which keep their current meaning.
     */
    public static final Counter PROXY_ERRORS_TOTAL = Counter.build()
            .name("testingbot_proxy_errors_total")
            .help("Proxy failures by classified reason, matching the X-TestingBot-Error header.")
            .labelNames("reason")
            .register();

    /**
     * Outbound connection attempts, by the path that made them ("proxy", "connect",
     * "websocket") and outcome ("success"/"failure").
     *
     * <p>We counted what arrived and nothing about what we dialled, so connection exhaustion
     * and a target that has started refusing were both invisible from the metrics alone.
     */
    public static final Counter DIAL_TOTAL = Counter.build()
            .name("testingbot_dial_total")
            .help("Outbound connection attempts by path and outcome.")
            .labelNames("path", "outcome")
            .register();

    /** How long establishing an outbound connection took, by path. */
    public static final Histogram DIAL_DURATION_SECONDS = Histogram.build()
            .name("testingbot_dial_duration_seconds")
            .help("Time to establish an outbound connection, by path.")
            .labelNames("path")
            .register();

    public static final Counter ERRORS_TOTAL = Counter.build()
            .name("testingbot_errors_total")
            .help("Tunnel errors by name.")
            .labelNames("name")
            .register();

    static {
        DefaultExports.initialize();
    }

    /**
     * Touch the class so static initializers (collector registration +
     * DefaultExports) run before the metrics endpoint serves its first scrape.
     */
    public static void init() {
        // no-op
    }

    public static void setTunnelInfo(float version, int tunnelId, String identifier) {
        TUNNEL_INFO.labels(
                Float.toString(version),
                Integer.toString(tunnelId),
                identifier == null ? "" : identifier
        ).set(1.0);
    }

    /**
     * Mirrors the {@code TUNNEL_UP} gauge so readiness can be answered without reading it back
     * out of the Prometheus registry. Set at every transition: tunnel established, connection
     * lost, reconnected, shut down.
     */
    private static volatile boolean tunnelUp;

    public static void setTunnelUp(boolean up) {
        tunnelUp = up;
        TUNNEL_UP.set(up ? 1.0 : 0.0);
    }

    /** True once the SSH tunnel is authenticated, forwarding and the local proxies are up. */
    public static boolean isTunnelUp() {
        return tunnelUp;
    }

    public static void refreshUptime(long startTimeMillis) {
        TUNNEL_UPTIME_SECONDS.set((System.currentTimeMillis() - startTimeMillis) / 1000.0);
    }
}
