package com.testingbot.tunnel;

import io.prometheus.client.Collector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tunnel identity metric must describe one tunnel, not every tunnel this process has had.
 *
 * <p>{@code tunnel_id} is a label, and the reconnect monitor rebuilds the tunnel -- taking a new
 * id -- after enough failed attempts. Each rebuild used to add a series that stayed at 1 for the
 * life of the process, so a long session reported several tunnels as simultaneously active and
 * the label set grew without bound.
 */
class TunnelInfoMetricTest {

    @AfterEach
    void tearDown() {
        TunnelMetrics.TUNNEL_INFO.clear();
    }

    private static List<Collector.MetricFamilySamples.Sample> samples() {
        return TunnelMetrics.TUNNEL_INFO.collect().get(0).samples;
    }

    @Test
    void rebuildingReplacesTheSeriesRatherThanAddingOne() {
        TunnelMetrics.setTunnelInfo(5.0f, 1001, "ci-run");
        TunnelMetrics.setTunnelInfo(5.0f, 1002, "ci-run");
        TunnelMetrics.setTunnelInfo(5.0f, 1003, "ci-run");

        assertThat(samples())
                .as("one active tunnel means one series")
                .hasSize(1);
        assertThat(samples().get(0).labelValues).contains("1003");
    }

    @Test
    void theSurvivingSeriesCarriesTheCurrentIdentity() {
        TunnelMetrics.setTunnelInfo(5.0f, 2001, "first");
        TunnelMetrics.setTunnelInfo(5.0f, 2002, "second");

        Collector.MetricFamilySamples.Sample sample = samples().get(0);
        assertThat(sample.labelValues).containsExactly("5.0", "2002", "second");
        assertThat(sample.value).isEqualTo(1.0);
    }

    @Test
    void aMissingIdentifierIsRecordedAsEmptyRatherThanNull() {
        // Prometheus label values cannot be null; an unnamed tunnel is the common case.
        TunnelMetrics.setTunnelInfo(5.0f, 3001, null);

        assertThat(samples()).hasSize(1);
        assertThat(samples().get(0).labelValues).containsExactly("5.0", "3001", "");
    }
}
