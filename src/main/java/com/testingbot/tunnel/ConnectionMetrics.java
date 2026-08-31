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
 * <p>IMPORTANT: these byte counters see HTTP connections only. Traffic inside a CONNECT tunnel
 * or a relayed WebSocket is NOT included, so on a tunnel carrying mostly HTTPS they account for
 * very little of the real volume -- measured at 134 bytes for a tunnel that carried 80 KiB.
 *
 * <p>That is a limitation of Jetty rather than a bug here: ConnectHandler's TunnelConnection
 * inherits AbstractConnection.getBytesIn()/getBytesOut(), which return -1, so a listener attached
 * to it has nothing to read. Counting relayed bytes needs a counting EndPoint wrapper around the
 * tunnel's endpoints, which is a piece of work in its own right.
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

    /**
     * Totals carried across connector restarts.
     *
     * <p>Jetty resets a ConnectionStatistics in doStart(), and the SSH reconnect restarts the
     * proxy connector, so the raw figures fall back to zero on every reconnect. Prometheus
     * counters must never go backwards -- a drop reads as a counter restart, so rate() spikes and
     * any increase() spanning a reconnect is wrong.
     *
     * <p>Detecting the reset here rather than banking it in HttpProxy.stop() also removes a race:
     * banking and resetting were two steps, and a scrape landing between them counted the same
     * bytes twice.
     */
    private final Map<String, long[]> carried = new LinkedHashMap<>();

    /** Registers {@code stats} under {@code name}; replaces any previous entry for that name. */
    public synchronized void add(String name, ConnectionStatistics stats) {
        ConnectionStatistics previous = listeners.put(name, stats);
        if (previous != null && previous != stats) {
            // A replacement listener starts from zero; keep what the old one had counted.
            observe(name, previous.getReceivedBytes() + previous.getSentBytes());
        }
    }

    /**
     * Folds {@code live} into the carried total for {@code name} and returns the monotonic sum.
     *
     * <p>A live figure lower than last time means the connector restarted and Jetty zeroed the
     * statistics, so whatever it had reached is banked before continuing.
     */
    /** Monotonic value for one counter series, banking whatever a restart zeroed. */
    private synchronized long monotonic(String key, long live) {
        long[] state = carried.computeIfAbsent(key, k -> new long[2]);
        if (live < state[1]) {
            state[0] += state[1];
        }
        state[1] = live;
        return state[0] + live;
    }

    private synchronized long observe(String name, long live) {
        long[] state = carried.computeIfAbsent(name, key -> new long[2]);
        if (live < state[1]) {
            state[0] += state[1];
        }
        state[1] = live;
        return state[0] + live;
    }

    @Override
    public synchronized List<MetricFamilySamples> collect() {
        List<String> labels = Collections.singletonList("listener");

        CounterMetricFamily received = new CounterMetricFamily(
                "testingbot_connection_bytes_received",
                "Bytes received on HTTP connections. Excludes traffic inside CONNECT tunnels.", labels);
        CounterMetricFamily sent = new CounterMetricFamily(
                "testingbot_connection_bytes_sent",
                "Bytes sent on HTTP connections. Excludes traffic inside CONNECT tunnels.", labels);
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
            String name = entry.getKey();
            List<String> value = Collections.singletonList(name);
            ConnectionStatistics stats = entry.getValue();
            // Counters have to survive the connector restart the SSH reconnect performs; gauges
            // describe the present moment and are read straight through.
            received.addMetric(value, monotonic(name + ":received", stats.getReceivedBytes()));
            sent.addMetric(value, monotonic(name + ":sent", stats.getSentBytes()));
            total.addMetric(value, monotonic(name + ":total", stats.getConnectionsTotal()));
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
     * Total bytes of tunnelled traffic moved in either direction; what the JSON status endpoint
     * reports.
     *
     * <p>Excludes the metrics listener. Scraping /metrics is our own overhead, not customer
     * traffic, and counting it would make a frequently-scraped tunnel look busier than it is.
     * The per-listener Prometheus series still report it.
     *
     * <p>Counts HTTP connections only; see the class comment on why tunnelled bytes are absent.
     */
    public synchronized long totalBytes() {
        long sum = 0;
        for (Map.Entry<String, ConnectionStatistics> entry : listeners.entrySet()) {
            long monotonic = observe(entry.getKey(),
                    entry.getValue().getReceivedBytes() + entry.getValue().getSentBytes());
            if (!METRICS.equals(entry.getKey())) {
                sum += monotonic;
            }
        }
        return sum;
    }
}
