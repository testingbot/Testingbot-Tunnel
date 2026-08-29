package com.testingbot.tunnel;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for InsightServer metrics endpoint
 */
class InsightServerTest {

    private App app;
    private InsightServer insightServer;
    private int metricsPort;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        // Reset statistics
        resetStatistics();

        app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");

        // Use dynamic port
        metricsPort = 0; // Will be assigned by OS
        app.setMetricsPort(metricsPort);

        objectMapper = new ObjectMapper();

        // Set initial statistics
        Statistics.setStartTime(System.currentTimeMillis() - 60000); // 60 seconds ago
        Statistics.addRequest();
        Statistics.addRequest();
        Statistics.addBytesTransferred(1024);
    }

    @AfterEach
    void tearDown() throws Exception {
        resetStatistics();
    }

    @Test
    void constructor_shouldStartServer() throws Exception {
        // Given: App with metrics port configured
        app.setMetricsPort(8999);

        // When: Creating InsightServer
        insightServer = new InsightServer(app);

        // Then: Server should start (verified by no exception)
        assertThat(insightServer).isNotNull();

        // Give server time to start
        Thread.sleep(500);
    }

    @Test
    void metricsEndpoint_shouldReturnJson() throws Exception {
        // Given: Running InsightServer
        app.setMetricsPort(8998);
        insightServer = new InsightServer(app);
        Thread.sleep(500); // Wait for server to start

        // When: Making request to metrics endpoint
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:8998/");
            client.execute(request, response -> {
                // Then: Should return 200 OK
                assertThat(response.getCode()).isEqualTo(200);

                // And: Content-Type should be JSON
                assertThat(response.getFirstHeader("Content-Type").getValue())
                    .contains("application/json");

                // And: Should contain valid JSON
                String body = EntityUtils.toString(response.getEntity());
                JsonNode json = objectMapper.readTree(body);

                assertThat(json.has("version")).isTrue();
                assertThat(json.has("uptime")).isTrue();
                assertThat(json.has("numberOfRequests")).isTrue();
                assertThat(json.has("bytesTransferred")).isTrue();
                return null;
            });
        }
    }

    @Test
    void metricsEndpoint_shouldReturnCorrectVersion() throws Exception {
        // Given: Running InsightServer
        app.setMetricsPort(8997);
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        // When: Getting metrics
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:8997/");
            client.execute(request, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode json = objectMapper.readTree(body);

                // Then: Version should match App.VERSION
                assertThat(json.get("version").asText()).isEqualTo(App.VERSION.toString());
                return null;
            });
        }
    }

    @Test
    void metricsEndpoint_shouldReturnUptime() throws Exception {
        // Given: Running InsightServer with known start time
        long startTime = System.currentTimeMillis() - 5000; // 5 seconds ago
        Statistics.setStartTime(startTime);

        app.setMetricsPort(8996);
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        // When: Getting metrics
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:8996/");
            client.execute(request, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode json = objectMapper.readTree(body);

                // Then: Uptime should be greater than 5000ms
                long uptime = Long.parseLong(json.get("uptime").asText());
                assertThat(uptime).isGreaterThanOrEqualTo(5000);
                return null;
            });
        }
    }

    @Test
    void metricsEndpoint_shouldReturnNumberOfRequests() throws Exception {
        // Given: Running InsightServer with known request count
        resetStatistics();
        Statistics.addRequest();
        Statistics.addRequest();
        Statistics.addRequest();

        app.setMetricsPort(8995);
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        // When: Getting metrics
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:8995/");
            client.execute(request, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode json = objectMapper.readTree(body);

                // Then: Should return correct request count
                assertThat(json.get("numberOfRequests").asText()).isEqualTo("3");
                return null;
            });
        }
    }

    @Test
    void metricsEndpoint_shouldReturnBytesTransferred() throws Exception {
        // Given: Running InsightServer with known bytes transferred
        resetStatistics();
        Statistics.addBytesTransferred(2048);

        app.setMetricsPort(8994);
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        // When: Getting metrics
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:8994/");
            client.execute(request, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode json = objectMapper.readTree(body);

                // Then: Should return correct bytes
                assertThat(json.get("bytesTransferred").asLong()).isEqualTo(2048);
                return null;
            });
        }
    }

    @Test
    void metricsEndpoint_shouldHandleMultipleRequests() throws Exception {
        // Given: Running InsightServer
        app.setMetricsPort(8993);
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        // When: Making multiple requests
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            for (int i = 0; i < 5; i++) {
                HttpGet request = new HttpGet("http://localhost:8993/");
                client.execute(request, response -> {
                    // Then: Each request should succeed
                    assertThat(response.getCode()).isEqualTo(200);
                    return null;
                });
            }
        }
    }

    /**
     * Reset static fields using reflection
     */
    private void resetStatistics() throws Exception {
        Statistics.reset();
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void prometheusEndpoint_shouldReturnExpositionFormat() throws Exception {
        int port = freePort();
        app.setMetricsPort(port);
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:" + port + "/metrics");
            client.execute(request, response -> {
                assertThat(response.getCode()).isEqualTo(200);
                assertThat(response.getFirstHeader("Content-Type").getValue())
                    .contains("text/plain");
                String body = EntityUtils.toString(response.getEntity());
                // Prometheus exposition: HELP/TYPE preamble plus our own metrics.
                assertThat(body).contains("# HELP");
                assertThat(body).contains("# TYPE");
                assertThat(body).contains("testingbot_");
                return null;
            });
        }
    }

    @Test
    void prometheusEndpoint_withAuth_shouldRejectAnonymous() throws Exception {
        int port = freePort();
        app.setMetricsPort(port);
        app.setMetricsAuth("user:secret");
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:" + port + "/metrics");
            client.execute(request, response -> {
                assertThat(response.getCode()).isEqualTo(401);
                assertThat(response.getFirstHeader("WWW-Authenticate").getValue())
                    .contains("Basic realm");
                return null;
            });
        }
    }

    @Test
    void prometheusEndpoint_withAuth_shouldAcceptCorrectCredentials() throws Exception {
        int port = freePort();
        app.setMetricsPort(port);
        app.setMetricsAuth("user:secret");
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        String credentials = java.util.Base64.getEncoder()
            .encodeToString("user:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:" + port + "/metrics");
            request.addHeader("Authorization", "Basic " + credentials);
            client.execute(request, response -> {
                assertThat(response.getCode()).isEqualTo(200);
                assertThat(EntityUtils.toString(response.getEntity())).contains("testingbot_");
                return null;
            });
        }
    }

    @Test
    void prometheusEndpoint_withAuth_shouldStillServeJsonStatusUnprotected() throws Exception {
        // Only /metrics is protected; the JSON status stays open, as before.
        int port = freePort();
        app.setMetricsPort(port);
        app.setMetricsAuth("user:secret");
        insightServer = new InsightServer(app);
        Thread.sleep(500);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet("http://localhost:" + port + "/");
            client.execute(request, response -> {
                assertThat(response.getCode()).isEqualTo(200);
                return null;
            });
        }
    }
}
