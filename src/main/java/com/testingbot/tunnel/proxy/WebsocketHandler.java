package com.testingbot.tunnel.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays WebSocket connections through the tunnel.
 *
 * <p>A WebSocket upgrade is, once the handshake is done, just a bidirectional byte tunnel --
 * exactly what {@link ConnectHandler} already provides for CONNECT. So rather than reimplementing
 * the selector, buffer pool and tunnel connections, this handler reuses ConnectHandler's machinery
 * and only customises the two things that genuinely differ from CONNECT:
 *
 * <ul>
 *   <li>the upgrade handshake must be replayed against the target before relaying starts
 *       ({@link #connectToServer}), and</li>
 *   <li>the client is answered with {@code 101 Switching Protocols} plus the target's handshake
 *       headers instead of {@code 200 OK} ({@link #onConnectSuccess}).</li>
 * </ul>
 */
public class WebsocketHandler extends ConnectHandler {
    protected static final Logger LOG = LoggerFactory.getLogger(WebsocketHandler.class);

    // Carries the target's handshake response headers from connectToServer() to onConnectSuccess().
    private static final String WS_RESPONSE_HEADERS_ATTRIBUTE =
            WebsocketHandler.class.getName() + ".wsResponseHeaders";

    private CustomDnsResolver dnsResolver;

    public WebsocketHandler() {
        super();
    }

    public void setDnsResolver(CustomDnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    /** Honours --dns for the WebSocket relay's outbound connection. */
    @Override
    protected InetSocketAddress newConnectAddress(String host, int port) {
        if (dnsResolver != null) {
            try {
                return new InetSocketAddress(dnsResolver.resolve(host)[0], port);
            } catch (java.net.UnknownHostException ex) {
                LOG.warn("Custom DNS could not resolve {}: {}", host, ex.getMessage());
            }
        }
        return super.newConnectAddress(host, port);
    }

    public WebsocketHandler(Handler handler) {
        super(handler);
    }

    static String buildUpgradeRequest(Request clientRequest) {
        StringBuilder requestHeaders = new StringBuilder();
        requestHeaders.append(clientRequest.getMethod()).append(" ").append(clientRequest.getHttpURI().getPath());
        if (clientRequest.getHttpURI().getQuery() != null) {
            requestHeaders.append("?").append(clientRequest.getHttpURI().getQuery());
        }
        requestHeaders.append(" ").append(clientRequest.getConnectionMetaData().getHttpVersion().asString()).append("\r\n");
        for (HttpField field : clientRequest.getHeaders()) {
            requestHeaders.append(field.getName()).append(": ").append(field.getValue()).append("\r\n");
        }
        requestHeaders.append("\r\n");
        return requestHeaders.toString();
    }

    private Map<String, String> performWebSocketHandshake(Request clientRequest, SocketChannel channel) throws IOException {
        // Send WebSocket upgrade request to target
        ByteBuffer writeBuffer = ByteBuffer.wrap(buildUpgradeRequest(clientRequest).getBytes(StandardCharsets.UTF_8));
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer);
        }

        // Read target's WebSocket handshake response (blocking)
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        StringBuilder responseSB = new StringBuilder();
        while (responseSB.indexOf("\r\n\r\n") < 0) {
            readBuffer.clear();
            int n = channel.read(readBuffer);
            if (n < 0) throw new IOException("Connection closed before WebSocket handshake completed");
            readBuffer.flip();
            byte[] bytes = new byte[readBuffer.remaining()];
            readBuffer.get(bytes);
            responseSB.append(new String(bytes, StandardCharsets.UTF_8));
        }

        String fullResponse = responseSB.toString();
        int headerEnd = fullResponse.indexOf("\r\n\r\n");
        String headerSection = fullResponse.substring(0, headerEnd);
        String[] lines = headerSection.split("\r\n");

        Map<String, String> wsResponseHeaders = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colonIndex = lines[i].indexOf(':');
            if (colonIndex > 0) {
                wsResponseHeaders.put(lines[i].substring(0, colonIndex).trim(), lines[i].substring(colonIndex + 1).trim());
            }
        }

        LOG.info("WebSocket handshake with target complete, status: {}, headers: {}", lines[0], wsResponseHeaders.size());
        return wsResponseHeaders;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String upgrade = request.getHeaders().get(HttpHeader.UPGRADE);
        if (upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim())) {
            if (request.getTunnelSupport() == null) {
                LOG.info("WS tunnelling not supported for {}", request);
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
                return true;
            }
            handleConnect(request, response, callback, request.getHttpURI().getAuthority());
            return true;
        }

        // Delegate straight to the wrapped handler rather than to super.handle(): this class
        // extends ConnectHandler purely to reuse its tunnelling machinery, and ConnectHandler's
        // own handle() would swallow CONNECT requests here -- they belong to the
        // CustomConnectHandler further down the chain, which adds fast-fail and upstream-proxy
        // support. This mirrors Handler.Wrapper.handle().
        Handler next = getHandler();
        return next != null && next.handle(request, response, callback);
    }

    /**
     * Connects to the target and completes the WebSocket handshake before handing the channel
     * back to ConnectHandler for relaying. The connect and handshake are blocking, so they run
     * on the executor rather than on the caller's thread.
     */
    @Override
    protected void connectToServer(Request request, String host, int port, Promise<SocketChannel> promise) {
        Promise<SocketChannel> timed =
                com.testingbot.tunnel.TunnelMetrics.timedDial("websocket", promise);
        getExecutor().execute(() -> {
            SocketChannel channel = null;
            try {
                // Connect to the target using blocking I/O for the handshake
                channel = SocketChannel.open();
                channel.socket().setTcpNoDelay(true);
                channel.socket().setSoTimeout((int) getConnectTimeout());
                channel.connect(new InetSocketAddress(host, port));

                Map<String, String> wsResponseHeaders = performWebSocketHandshake(request, channel);

                // Switch to non-blocking for the async relay
                channel.configureBlocking(false);
                request.setAttribute(WS_RESPONSE_HEADERS_ATTRIBUTE, wsResponseHeaders);

                timed.succeeded(channel);
            } catch (Throwable x) {
                close(channel);
                LOG.warn("WebSocket connect/handshake failed", x);
                timed.failed(x);
            }
        });
    }

    /**
     * Mirrors {@link ConnectHandler}'s tunnel wiring, but answers the client with
     * {@code 101 Switching Protocols} and the target's handshake headers.
     */
    @Override
    protected void onConnectSuccess(ConnectContext connectContext, UpstreamConnection upstreamConnection) {
        ConcurrentMap<String, Object> context = connectContext.getContext();
        Request request = connectContext.getRequest();
        prepareContext(request, context);

        EndPoint downstreamEndPoint = connectContext.getEndPoint();
        DownstreamConnection downstreamConnection = newDownstreamConnection(downstreamEndPoint, context);
        downstreamConnection.setInputBufferSize(getBufferSize());

        upstreamConnection.setConnection(downstreamConnection);
        downstreamConnection.setConnection(upstreamConnection);
        LOG.info("Connection setup completed: {}<->{}", downstreamConnection, upstreamConnection);

        Response response = connectContext.getResponse();
        Callback callback = connectContext.getCallback();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> wsResponseHeaders =
                    (Map<String, String>) request.getAttribute(WS_RESPONSE_HEADERS_ATTRIBUTE);
            if (wsResponseHeaders != null) {
                for (Map.Entry<String, String> entry : wsResponseHeaders.entrySet()) {
                    response.getHeaders().put(entry.getKey(), entry.getValue());
                }
            }
            response.setStatus(HttpStatus.SWITCHING_PROTOCOLS_101);

            // Hand the tunnel to Jetty so it swaps the connection once the 101 is flushed.
            // Must be set before completing the callback.
            request.setAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, downstreamConnection);
            LOG.info("Upgraded connection to {}", downstreamConnection);

            callback.succeeded();
        } catch (Throwable x) {
            LOG.warn("Could not send WebSocket upgrade response", x);
            callback.failed(x);
        }
    }

    private void close(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable x) {
            LOG.trace("IGNORED", x);
        }
    }
}
