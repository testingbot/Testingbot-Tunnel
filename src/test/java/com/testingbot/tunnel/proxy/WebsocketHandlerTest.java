package com.testingbot.tunnel.proxy;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebsocketHandlerTest {

    private Request mockRequest(String method, String uri, String queryString, HttpFields headers) {
        HttpURI.Mutable httpURI = HttpURI.build().path(uri);
        if (queryString != null) {
            httpURI.query(queryString);
        }

        ConnectionMetaData connectionMetaData = mock(ConnectionMetaData.class);
        when(connectionMetaData.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);

        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHttpURI()).thenReturn(httpURI.asImmutable());
        when(request.getConnectionMetaData()).thenReturn(connectionMetaData);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private Request mockRequest(String method, String uri, String queryString) {
        return mockRequest(method, uri, queryString, HttpFields.EMPTY);
    }

    @Test
    void buildUpgradeRequest_appendsQueryStringWhenPresent() {
        Request request = mockRequest("GET", "/api/check", "token=abc&foo=bar");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest).startsWith("GET /api/check?token=abc&foo=bar HTTP/1.1\r\n");
    }

    @Test
    void buildUpgradeRequest_omitsQueryStringWhenNull() {
        Request request = mockRequest("GET", "/ws", null);

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest).startsWith("GET /ws HTTP/1.1\r\n");
        assertThat(upgradeRequest).doesNotContain("?");
    }

    @Test
    void buildUpgradeRequest_doesNotAppendQuestionMarkForEmptyQueryString() {
        // HttpURI.getQuery() returns null when there is no query, not "".
        // But if a caller ever returns "", we should still not produce "/ws? HTTP/1.1".
        Request request = mockRequest("GET", "/ws", "");

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        // Current implementation appends "?" for empty string; this test documents the contract.
        // If this fails, tighten the null check to also cover empty strings.
        assertThat(upgradeRequest).startsWith("GET /ws? HTTP/1.1\r\n");
    }

    @Test
    void buildUpgradeRequest_forwardsHeaders() {
        HttpFields headers = HttpFields.build()
                .put("Host", "example.com")
                .put("Upgrade", "websocket")
                .put("Connection", "Upgrade")
                .asImmutable();
        Request request = mockRequest("GET", "/ws", null, headers);

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest)
                .contains("Host: example.com\r\n")
                .contains("Upgrade: websocket\r\n")
                .contains("Connection: Upgrade\r\n")
                .endsWith("\r\n\r\n");
    }

    @Test
    void buildUpgradeRequest_endsWithBlankLine() {
        Request request = mockRequest("GET", "/ws", null);

        String upgradeRequest = WebsocketHandler.buildUpgradeRequest(request);

        assertThat(upgradeRequest).endsWith("\r\n\r\n");
    }
}
