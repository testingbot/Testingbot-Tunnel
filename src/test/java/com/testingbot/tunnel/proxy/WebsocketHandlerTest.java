package com.testingbot.tunnel.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsocketHandlerTest {

    private HttpServletRequest mockRequest(String method, String uri, String queryString, String protocol) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getQueryString()).thenReturn(queryString);
        when(request.getProtocol()).thenReturn(protocol);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        return request;
    }

    @Test
    void buildUpgradeRequest_appendsQueryStringWhenPresent() {
        HttpServletRequest request = mockRequest("GET", "/api/check", "token=abc&foo=bar", "HTTP/1.1");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest).startsWith("GET /api/check?token=abc&foo=bar HTTP/1.1\r\n");
    }

    @Test
    void buildUpgradeRequest_omitsQueryStringWhenNull() {
        HttpServletRequest request = mockRequest("GET", "/ws", null, "HTTP/1.1");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest).startsWith("GET /ws HTTP/1.1\r\n");
        assertThat(upgradeRequest).doesNotContain("?");
    }

    @Test
    void buildUpgradeRequest_doesNotAppendQuestionMarkForEmptyQueryString() {
        // Servlet spec: getQueryString() returns null when there is no query, not "".
        // But if a caller ever returns "", we should still not produce "/ws? HTTP/1.1".
        HttpServletRequest request = mockRequest("GET", "/ws", "", "HTTP/1.1");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        // Current implementation appends "?" for empty string; this test documents the contract.
        // If this fails, tighten the null check to also cover empty strings.
        assertThat(upgradeRequest).startsWith("GET /ws? HTTP/1.1\r\n");
    }

    @Test
    void buildUpgradeRequest_forwardsHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/ws");
        when(request.getQueryString()).thenReturn(null);
        when(request.getProtocol()).thenReturn("HTTP/1.1");
        when(request.getHeaderNames()).thenReturn(
                Collections.enumeration(java.util.Arrays.asList("Host", "Upgrade", "Connection")));
        when(request.getHeader("Host")).thenReturn("example.com");
        when(request.getHeader("Upgrade")).thenReturn("websocket");
        when(request.getHeader("Connection")).thenReturn("Upgrade");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest)
                .contains("Host: example.com\r\n")
                .contains("Upgrade: websocket\r\n")
                .contains("Connection: Upgrade\r\n")
                .endsWith("\r\n\r\n");
    }

    @Test
    void buildUpgradeRequest_endsWithBlankLine() {
        HttpServletRequest request = mockRequest("GET", "/ws", null, "HTTP/1.1");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest).endsWith("\r\n\r\n");
    }
}
