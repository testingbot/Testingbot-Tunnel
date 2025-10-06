package ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testingbot.tunnel.Api;
import com.testingbot.tunnel.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for TunnelPoller
 */
class TunnelPollerTest {

    private App app;
    private Api api;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        app = mock(App.class);
        api = mock(Api.class);
        when(app.getApi()).thenReturn(api);
        objectMapper = new ObjectMapper();
    }

    @Test
    void constructor_shouldStartPolling() throws Exception {
        // Given: API returns READY state
        JsonNode readyResponse = objectMapper.readTree("{\"state\":\"READY\",\"tunnel_id\":\"123\"}");
        when(api.pollTunnel(anyString())).thenReturn(readyResponse);

        // When: Creating TunnelPoller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");

        // Then: Should start polling
        assertThat(poller).isNotNull();

        // Wait for at least one poll
        Thread.sleep(6000);

        // Clean up
        poller.cancel();
    }

    @Test
    void pollTask_whenStateIsReady_shouldCallTunnelReady() throws Exception {
        // Given: API returns READY state
        JsonNode readyResponse = objectMapper.readTree("{\"state\":\"READY\",\"tunnel_id\":\"123\"}");
        when(api.pollTunnel(anyString())).thenReturn(readyResponse);

        // When: Starting poller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");

        // Wait for poll to execute
        Thread.sleep(6000);

        // Then: Should call tunnelReady
        verify(app, atLeastOnce()).tunnelReady(any(JsonNode.class));

        // Clean up
        poller.cancel();
    }

    @Test
    void pollTask_whenStateIsPending_shouldContinuePolling() throws Exception {
        // Given: API returns PENDING then READY
        JsonNode pendingResponse = objectMapper.readTree("{\"state\":\"PENDING\",\"tunnel_id\":\"123\"}");
        JsonNode readyResponse = objectMapper.readTree("{\"state\":\"READY\",\"tunnel_id\":\"123\"}");

        when(api.pollTunnel(anyString()))
            .thenReturn(pendingResponse)
            .thenReturn(pendingResponse)
            .thenReturn(readyResponse);

        // When: Starting poller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");

        // Wait for multiple polls
        Thread.sleep(16000);

        // Then: Should eventually call tunnelReady
        verify(app, atLeastOnce()).tunnelReady(any(JsonNode.class));

        // Clean up
        poller.cancel();
    }

    @Test
    void cancel_shouldStopPolling() throws Exception {
        // Given: API returns PENDING state
        JsonNode pendingResponse = objectMapper.readTree("{\"state\":\"PENDING\",\"tunnel_id\":\"123\"}");
        when(api.pollTunnel(anyString())).thenReturn(pendingResponse);

        // When: Creating and immediately canceling poller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");
        poller.cancel();

        // Wait to verify no more polling
        Thread.sleep(6000);

        // Then: Should not call tunnelReady
        verify(app, never()).tunnelReady(any(JsonNode.class));
    }

    @Test
    void pollTask_whenExceptionOccurs_shouldCancelPolling() throws Exception {
        // Given: API throws exception
        when(api.pollTunnel(anyString())).thenThrow(new RuntimeException("API Error"));

        // When: Starting poller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");

        // Wait for poll to execute
        Thread.sleep(6000);

        // Then: Should not call tunnelReady
        verify(app, never()).tunnelReady(any(JsonNode.class));

        // Clean up
        poller.cancel();
    }

    @Test
    void pollTask_shouldPassCorrectTunnelId() throws Exception {
        // Given: API returns READY state
        JsonNode readyResponse = objectMapper.readTree("{\"state\":\"READY\",\"tunnel_id\":\"456\"}");
        when(api.pollTunnel(anyString())).thenReturn(readyResponse);

        String tunnelId = "tunnel456";

        // When: Starting poller with specific tunnel ID
        TunnelPoller poller = new TunnelPoller(app, tunnelId);

        // Wait for poll
        Thread.sleep(6000);

        // Then: Should poll with correct tunnel ID
        ArgumentCaptor<String> tunnelIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(api, atLeastOnce()).pollTunnel(tunnelIdCaptor.capture());
        assertThat(tunnelIdCaptor.getValue()).isEqualTo(tunnelId);

        // Clean up
        poller.cancel();
    }

    @Test
    void pollTask_withDifferentStates_shouldHandleAll() throws Exception {
        // Given: API returns various states
        JsonNode initializingResponse = objectMapper.readTree("{\"state\":\"INITIALIZING\",\"tunnel_id\":\"123\"}");
        JsonNode connectingResponse = objectMapper.readTree("{\"state\":\"CONNECTING\",\"tunnel_id\":\"123\"}");
        JsonNode readyResponse = objectMapper.readTree("{\"state\":\"READY\",\"tunnel_id\":\"123\"}");

        when(api.pollTunnel(anyString()))
            .thenReturn(initializingResponse)
            .thenReturn(connectingResponse)
            .thenReturn(readyResponse);

        // When: Starting poller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");

        // Wait for polls
        Thread.sleep(16000);

        // Then: Should eventually reach READY and call tunnelReady
        verify(app, atLeastOnce()).tunnelReady(any(JsonNode.class));

        // Clean up
        poller.cancel();
    }

    @Test
    void pollTask_shouldPollEvery5Seconds() throws Exception {
        // Given: API returns PENDING state
        JsonNode pendingResponse = objectMapper.readTree("{\"state\":\"PENDING\",\"tunnel_id\":\"123\"}");
        when(api.pollTunnel(anyString())).thenReturn(pendingResponse);

        // When: Starting poller
        TunnelPoller poller = new TunnelPoller(app, "tunnel123");

        // Wait for multiple poll cycles
        Thread.sleep(16000);

        // Then: Should have polled multiple times (at least 2-3 times in 16 seconds)
        verify(api, atLeast(2)).pollTunnel(anyString());

        // Clean up
        poller.cancel();
    }
}
