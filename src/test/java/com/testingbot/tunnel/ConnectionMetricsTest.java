package com.testingbot.tunnel;

import io.prometheus.client.Collector;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionMetricsTest {

    /**
     * Finds a family by the series name a scraper sees. simpleclient strips "_total" from the
     * family name of a counter while the sample keeps it, so matching on family name alone
     * silently misses counters.
     */
    private static Collector.MetricFamilySamples bySampleName(
            List<Collector.MetricFamilySamples> all, String sampleName) {
        return all.stream()
                .filter(f -> f.samples.stream().anyMatch(sample -> sample.name.equals(sampleName))
                        || (f.samples.isEmpty() && sampleName.startsWith(f.name)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no family exposing " + sampleName));
    }

    private static List<String> exposedSeries(List<Collector.MetricFamilySamples> all) {
        return all.stream()
                .map(f -> f.type == Collector.Type.COUNTER ? f.name + "_total" : f.name)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void everyListenerGetsItsOwnSeries() {
        // The whole point of the label: proxy load and a wedged Selenium relay must be
        // distinguishable, and before this they shared one unlabelled number.
        ConnectionMetrics metrics = new ConnectionMetrics();
        metrics.add(ConnectionMetrics.PROXY, new ConnectionStatistics());
        metrics.add(ConnectionMetrics.SELENIUM, new ConnectionStatistics());
        metrics.add(ConnectionMetrics.METRICS, new ConnectionStatistics());

        List<Collector.MetricFamilySamples> collected = metrics.collect();

        assertThat(bySampleName(collected, "testingbot_connections_total").samples)
                .hasSize(3)
                .allSatisfy(s -> assertThat(s.labelNames).containsExactly("listener"));
        assertThat(bySampleName(collected, "testingbot_connections_total").samples)
                .extracting(s -> s.labelValues.get(0))
                .containsExactlyInAnyOrder("proxy", "selenium", "metrics");
    }

    @Test
    void exposesTheFamiliesTheDashboardNeeds() {
        ConnectionMetrics metrics = new ConnectionMetrics();
        metrics.add(ConnectionMetrics.PROXY, new ConnectionStatistics());

        // Asserts the names a scraper actually sees, not the internal family names.
        assertThat(exposedSeries(metrics.collect())).containsExactlyInAnyOrder(
                "testingbot_connection_bytes_received_total",
                "testingbot_connection_bytes_sent_total",
                "testingbot_connections_total",
                "testingbot_connections_current",
                "testingbot_connections_max");
    }

    @Test
    void totalBytesSumsEveryListener() {
        // Statistics.getBytesTransferred() reads this, so it must not report one listener.
        ConnectionMetrics metrics = new ConnectionMetrics();
        SettableStats proxy = new SettableStats();
        SettableStats selenium = new SettableStats();
        metrics.add(ConnectionMetrics.PROXY, proxy);
        metrics.add(ConnectionMetrics.SELENIUM, selenium);

        // Distinct non-zero values, because two fresh (zero) statistics cannot tell summing
        // apart from returning a constant or reading only one listener -- which is the entire
        // contract here, since Statistics.getBytesTransferred() reads it.
        proxy.set(100, 7, 1);
        selenium.set(20, 3, 1);

        assertThat(metrics.totalBytes()).isEqualTo(130);
        assertThat(metrics.collect()).isNotEmpty();
    }

    @Test
    void addingTheSameListenerTwice_replacesRatherThanDuplicates() {
        // The proxy can be restarted inside one process; duplicate series would break scrapes.
        ConnectionMetrics metrics = new ConnectionMetrics();
        metrics.add(ConnectionMetrics.PROXY, new ConnectionStatistics());
        metrics.add(ConnectionMetrics.PROXY, new ConnectionStatistics());

        assertThat(bySampleName(metrics.collect(), "testingbot_connections_total").samples).hasSize(1);
    }

    @Test
    void emptyCollector_producesNoSamples() {
        ConnectionMetrics metrics = new ConnectionMetrics();

        assertThat(metrics.collect()).allSatisfy(f -> assertThat(f.samples).isEmpty());
        assertThat(metrics.totalBytes()).isEqualTo(0);
    }

    /** Statistics with settable totals; the real ones are driven by actual connections. */
    private static final class SettableStats extends org.eclipse.jetty.io.ConnectionStatistics {
        private long received;
        private long sent;

        @Override
        public long getReceivedBytes() {
            return received;
        }

        @Override
        public long getSentBytes() {
            return sent;
        }

        void set(long received, long sent, long ignoredConnections) {
            this.received = received;
            this.sent = sent;
        }
    }
}
