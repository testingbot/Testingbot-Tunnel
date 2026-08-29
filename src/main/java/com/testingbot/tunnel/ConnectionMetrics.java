package com.testingbot.tunnel;

import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import io.prometheus.client.GaugeMetricFamily;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.io.ConnectionStatistics;

/**
 * Exposes Jetty's {@link ConnectionStatistics} to Prometheus, one series per listener.
 *
 * <p>Byte counters used to be incremented from the proxy's per-response callback, which only
 * ever saw plain HTTP; everything tunnelled -- CONNECT and relayed WebSocket traffic, most of
 * what a tunnel actually carries -- went uncounted. Reading the connector's own statistics
 * counts bytes wherever they came from.
 *
 * <p>The {@code listener} label separates the local proxy from the Selenium forwarder and the
 * metrics endpoint. Without it "are connections piling up?" cannot distinguish a wedged
 * Selenium relay from ordinary proxy load, which are very different problems.
 *
 * <p>Sampled at scrape time rather than pushed, so there is no per-request bookkeeping.
 */
public final class ConnectionMetrics extends Collector {

    /** Listener names used as the {@code listener} label. */
    public static final String PROXY = "proxy";
    public static final String SELENIUM = "selenium";
    public static final String METRICS = "metrics";

    private final Map<String, ConnectionStatistics> listeners = new LinkedHashMap<>();

    /** Registers {@code stats} under {@code name}; replaces any previous entry for that name. */
    public synchronized void add(String name, ConnectionStatistics stats) {
        listeners.put(name, stats);
    }

    @Override
    public synchronized List<MetricFamilySamples> collect() {
        List<String> labels = Collections.singletonList("listener");

        CounterMetricFamily received = new CounterMetricFamily(
                "testingbot_connection_bytes_received",
                "Bytes received on a listener, including tunnelled traffic.", labels);
        CounterMetricFamily sent = new CounterMetricFamily(
                "testingbot_connection_bytes_sent",
                "Bytes sent on a listener, including tunnelled traffic.", labels);
        CounterMetricFamily total = new CounterMetricFamily(
                "testingbot_connections_total",
                "Connections accepted on a listener since startup.", labels);
        GaugeMetricFamily current = new GaugeMetricFamily(
                "testingbot_connections_current",
                "Connections currently open on a listener.", labels);
        GaugeMetricFamily max = new GaugeMetricFamily(
                "testingbot_connections_max",
                "Peak concurrent connections on a listener.", labels);

        for (Map.Entry<String, ConnectionStatistics> entry : listeners.entrySet()) {
            List<String> value = Collections.singletonList(entry.getKey());
            ConnectionStatistics stats = entry.getValue();
            received.addMetric(value, stats.getReceivedBytes());
            sent.addMetric(value, stats.getSentBytes());
            total.addMetric(value, stats.getConnectionsTotal());
            current.addMetric(value, stats.getConnections());
            max.addMetric(value, stats.getConnectionsMax());
        }

        List<MetricFamilySamples> out = new ArrayList<>();
        out.add(received);
        out.add(sent);
        out.add(total);
        out.add(current);
        out.add(max);
        return Collections.unmodifiableList(out);
    }

    /**
     * Total bytes moved in either direction across every listener; what the JSON status
     * endpoint reports.
     */
    public synchronized long totalBytes() {
        long sum = 0;
        for (ConnectionStatistics stats : listeners.values()) {
            sum += stats.getReceivedBytes() + stats.getSentBytes();
        }
        return sum;
    }
}
