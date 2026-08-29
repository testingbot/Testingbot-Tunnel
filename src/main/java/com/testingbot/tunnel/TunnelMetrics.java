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
     * Registers connector-level connection statistics. Idempotent: the local proxy can be
     * restarted within one process, and Prometheus rejects duplicate registrations.
     */
    public static synchronized void registerConnectionMetrics(ConnectionMetrics metrics) {
        if (connectionMetrics != null) {
            io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(connectionMetrics);
        }
        connectionMetrics = metrics;
        metrics.register();
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

    public static void setTunnelUp(boolean up) {
        TUNNEL_UP.set(up ? 1.0 : 0.0);
    }

    public static void refreshUptime(long startTimeMillis) {
        TUNNEL_UPTIME_SECONDS.set((System.currentTimeMillis() - startTimeMillis) / 1000.0);
    }
}
