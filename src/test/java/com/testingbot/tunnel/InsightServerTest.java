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
        Field requestsField = Statistics.class.getDeclaredField("numberOfRequests");
        requestsField.setAccessible(true);
        requestsField.setLong(null, 0);

        Field bytesField = Statistics.class.getDeclaredField("bytesTransferred");
        bytesField.setAccessible(true);
        bytesField.setLong(null, 0);

        Field startTimeField = Statistics.class.getDeclaredField("startTime");
        startTimeField.setAccessible(true);
        startTimeField.setLong(null, 0);
    }
}
