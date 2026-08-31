package com.testingbot.tunnel;

import io.prometheus.client.Collector;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connection counters must never go backwards.
 *
 * <p>Jetty resets a ConnectionStatistics in doStart(), and the SSH reconnect restarts the proxy
 * connector, so the raw figures return to zero on every reconnect. Prometheus reads a drop as a
 * counter restart: rate() spikes and any increase() spanning the reconnect is wrong. The
 * accounting therefore has to notice the reset and carry what came before.
 */
class ConnectionMetricsMonotonicTest {

    /** Stands in for a connector's statistics, with a settable pair of totals. */
    private static final class FakeStats extends ConnectionStatistics {
        private long received;
        private long sent;
        private long connections;

        @Override
        public long getReceivedBytes() {
            return received;
        }

        @Override
        public long getSentBytes() {
            return sent;
        }

        @Override
        public long getConnectionsTotal() {
            return connections;
        }

        void set(long received, long sent, long connections) {
            this.received = received;
            this.sent = sent;
            this.connections = connections;
        }
    }

    /**
     * Counter samples carry a {@code _total} suffix the family name does not, so match either
     * spelling rather than depending on which side of that quirk a metric falls.
     */
    private static double sampleValue(ConnectionMetrics metrics, String name, String listener) {
        for (Collector.MetricFamilySamples family : metrics.collect()) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                boolean matches = sample.name.equals(name) || sample.name.equals(name + "_total");
                if (matches && sample.labelValues.contains(listener)) {
                    return sample.value;
                }
            }
        }
        return -1;
    }

    @Test
    void totalBytesSurvivesAConnectorRestart() {
        ConnectionMetrics metrics = new ConnectionMetrics();
        FakeStats stats = new FakeStats();
        metrics.add(ConnectionMetrics.PROXY, stats);

        stats.set(600, 400, 3);
        assertThat(metrics.totalBytes()).isEqualTo(1000);

        // What Jetty does on restart.
        stats.set(0, 0, 0);
        assertThat(metrics.totalBytes())
                .as("the total must not fall back to zero")
                .isEqualTo(1000);

        stats.set(50, 25, 1);
        assertThat(metrics.totalBytes()).isEqualTo(1075);
    }

    @Test
    void prometheusCountersSurviveAConnectorRestart() {
        ConnectionMetrics metrics = new ConnectionMetrics();
        FakeStats stats = new FakeStats();
        metrics.add(ConnectionMetrics.PROXY, stats);

        stats.set(600, 400, 3);
        assertThat(sampleValue(metrics, "testingbot_connection_bytes_received",
                ConnectionMetrics.PROXY)).isEqualTo(600);
        assertThat(sampleValue(metrics, "testingbot_connections_total",
                ConnectionMetrics.PROXY)).isEqualTo(3);

        stats.set(0, 0, 0);
        stats.set(10, 5, 1);

        assertThat(sampleValue(metrics, "testingbot_connection_bytes_received",
                ConnectionMetrics.PROXY)).isEqualTo(610);
        assertThat(sampleValue(metrics, "testingbot_connection_bytes_sent",
                ConnectionMetrics.PROXY)).isEqualTo(405);
        assertThat(sampleValue(metrics, "testingbot_connections_total",
                ConnectionMetrics.PROXY)).isEqualTo(4);
    }

    @Test
    void gaugesStillDescribeThePresentMoment() {
        // Only counters are carried; a gauge that never came down would be worse than useless.
        ConnectionMetrics metrics = new ConnectionMetrics();
        FakeStats stats = new FakeStats();
        metrics.add(ConnectionMetrics.PROXY, stats);
        stats.set(100, 100, 5);
        metrics.collect();

        stats.set(0, 0, 0);

        assertThat(sampleValue(metrics, "testingbot_connections_current",
                ConnectionMetrics.PROXY)).isZero();
    }

    @Test
    void replacingAListenerKeepsWhatTheOldOneCounted() {
        // App.boot() builds a fresh HttpProxy on a rebuild, registering a new statistics object.
        ConnectionMetrics metrics = new ConnectionMetrics();
        FakeStats first = new FakeStats();
        metrics.add(ConnectionMetrics.PROXY, first);
        first.set(700, 300, 2);
        assertThat(metrics.totalBytes()).isEqualTo(1000);

        FakeStats second = new FakeStats();
        metrics.add(ConnectionMetrics.PROXY, second);
        second.set(20, 30, 1);

        assertThat(metrics.totalBytes()).isEqualTo(1050);
    }

    @Test
    void theMetricsListenerIsStillExcludedFromTheTotal() {
        ConnectionMetrics metrics = new ConnectionMetrics();
        FakeStats proxy = new FakeStats();
        FakeStats scrapes = new FakeStats();
        metrics.add(ConnectionMetrics.PROXY, proxy);
        metrics.add(ConnectionMetrics.METRICS, scrapes);

        proxy.set(100, 100, 1);
        scrapes.set(9000, 9000, 50);

        assertThat(metrics.totalBytes()).isEqualTo(200);
    }
}
