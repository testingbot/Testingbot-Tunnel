package com.testingbot.tunnel;

import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import io.prometheus.client.GaugeMetricFamily;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jetty.io.ConnectionStatistics;

/**
 * Exposes Jetty's {@link ConnectionStatistics} to Prometheus.
 *
 * <p>Byte counters used to be incremented from the proxy servlet's response callback, which
 * only ever saw plain HTTP. Everything tunnelled -- HTTPS CONNECT and relayed WebSocket
 * traffic, i.e. most of what a tunnel actually carries -- went uncounted. Reading the
 * connector's own statistics counts bytes wherever they came from.
 *
 * <p>Sampled at scrape time rather than pushed, so there is no per-request bookkeeping.
 */
public final class ConnectionMetrics extends Collector {

    private final ConnectionStatistics stats;

    public ConnectionMetrics(ConnectionStatistics stats) {
        this.stats = stats;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        List<MetricFamilySamples> out = new ArrayList<>();
        out.add(new CounterMetricFamily(
                "testingbot_connection_bytes_received",
                "Bytes received from clients on the local proxy connector, including tunnelled traffic.",
                stats.getReceivedBytes()));
        out.add(new CounterMetricFamily(
                "testingbot_connection_bytes_sent",
                "Bytes sent to clients on the local proxy connector, including tunnelled traffic.",
                stats.getSentBytes()));
        out.add(new CounterMetricFamily(
                "testingbot_connections_total",
                "Connections accepted by the local proxy connector since startup.",
                stats.getConnectionsTotal()));
        out.add(new GaugeMetricFamily(
                "testingbot_connections_max",
                "Peak number of concurrent connections on the local proxy connector.",
                stats.getConnectionsMax()));
        return Collections.unmodifiableList(out);
    }

    /** Total bytes moved in either direction; what the JSON status endpoint reports. */
    public long totalBytes() {
        return stats.getReceivedBytes() + stats.getSentBytes();
    }
}
