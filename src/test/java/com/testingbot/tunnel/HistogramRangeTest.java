package com.testingbot.tunnel;

import io.prometheus.client.Collector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The size and duration histograms must still distinguish transfers at the top of their range.
 *
 * <p>A tunnel carries whatever the site under test serves. With the previous buckets -- 16 MiB
 * for size, Prometheus' default 10 seconds for duration -- every large or slow transfer landed in
 * the same +Inf bucket, so the metric said nothing about exactly the requests worth
 * investigating.
 */
class HistogramRangeTest {

    /**
     * Prometheus emits no samples for a label combination until it has been used, so the child
     * has to exist before the buckets can be inspected at all.
     */
    @org.junit.jupiter.api.BeforeEach
    void touchChildren() {
        TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES.labels("GET");
        TunnelMetrics.HTTP_REQUEST_SIZE_BYTES.labels("GET");
        TunnelMetrics.HTTP_REQUEST_DURATION_SECONDS.labels("GET");
    }

    /** The largest finite bucket boundary, i.e. the point past which detail is lost. */
    private static double topFiniteBucket(Collector collector, String sampleName) {
        double top = 0;
        for (Collector.MetricFamilySamples family : collector.collect()) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                if (!sample.name.equals(sampleName)) {
                    continue;
                }
                int index = sample.labelNames.indexOf("le");
                if (index < 0) {
                    continue;
                }
                String le = sample.labelValues.get(index);
                if (!le.equals("+Inf")) {
                    top = Math.max(top, Double.parseDouble(le));
                }
            }
        }
        return top;
    }

    /** Observations at or below {@code le}, for the given bucket boundary. */
    private static double bucketCount(Collector collector, String sampleName, String le) {
        for (Collector.MetricFamilySamples family : collector.collect()) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                int index = sample.labelNames.indexOf("le");
                if (sample.name.equals(sampleName) && index >= 0
                        && sample.labelValues.get(index).equals(le)) {
                    return sample.value;
                }
            }
        }
        return -1;
    }

    @Test
    void payloadSizesReachAGigabyte() {
        // 16 MiB was the old ceiling; a build artefact or a video clears it easily.
        assertThat(topFiniteBucket(TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES,
                "testingbot_http_response_size_bytes_bucket"))
                .isGreaterThanOrEqualTo(1_073_741_824.0);
        assertThat(topFiniteBucket(TunnelMetrics.HTTP_REQUEST_SIZE_BYTES,
                "testingbot_http_request_size_bytes_bucket"))
                .isGreaterThanOrEqualTo(1_073_741_824.0);
    }

    @Test
    void aHundredMegabyteResponseIsNotLostInTheOverflowBucket() {
        double before = bucketCount(TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES,
                "testingbot_http_response_size_bytes_bucket", "2.68435456E8");

        TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES.labels("GET").observe(100L * 1024 * 1024);

        assertThat(bucketCount(TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES,
                "testingbot_http_response_size_bytes_bucket", "2.68435456E8"))
                .as("a 100 MiB response should land in a finite bucket")
                .isEqualTo(before + 1);
    }

    @Test
    void durationsReachTenMinutes() {
        // The default ceiling of 10 seconds is well below a large download over a slow link.
        assertThat(topFiniteBucket(TunnelMetrics.HTTP_REQUEST_DURATION_SECONDS,
                "testingbot_http_request_duration_seconds_bucket"))
                .isGreaterThanOrEqualTo(600.0);
    }

    @Test
    void aTwoMinuteRequestIsNotLostInTheOverflowBucket() {
        double before = bucketCount(TunnelMetrics.HTTP_REQUEST_DURATION_SECONDS,
                "testingbot_http_request_duration_seconds_bucket", "120.0");

        TunnelMetrics.HTTP_REQUEST_DURATION_SECONDS.labels("GET").observe(119.0);

        assertThat(bucketCount(TunnelMetrics.HTTP_REQUEST_DURATION_SECONDS,
                "testingbot_http_request_duration_seconds_bucket", "120.0"))
                .isEqualTo(before + 1);
    }

    @Test
    void theBucketCountStaysModest() {
        // Buckets multiply by the method label, so the range must not be bought with cardinality.
        List<Collector.MetricFamilySamples> families =
                TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES.collect();
        long buckets = families.get(0).samples.stream()
                .filter(s -> s.name.endsWith("_bucket"))
                .count();

        assertThat(buckets).isLessThanOrEqualTo(20);
    }
}
