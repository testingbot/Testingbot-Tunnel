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
    /** Cap on the handshake response we will buffer before giving up. */
    private static final int MAX_HANDSHAKE_BYTES = 16 * 1024;

    private static final String WS_RESPONSE_HEADERS_ATTRIBUTE =
            WebsocketHandler.class.getName() + ".wsResponseHeaders";

    private CustomDnsResolver dnsResolver;
    private ConnectToMap connectTo = ConnectToMap.none();

    public WebsocketHandler() {
        super();
    }

    /**
     * Jetty's {@link ConnectHandler#doStart()} calls {@code addBean(newSelectorManager())} on
     * every start and never removes it, while ContainerLifeCycle.doStop() only stops beans. The
     * SSH reconnect restarts this server in place, so each cycle left another live
     * SelectorManager behind, each holding a pool thread parked in select() forever.
     *
     * <p>Measured before this override: every stop/start added two selectors and two permanently
     * busy threads, and after 88 reconnects the proxy could not start at all --
     * "Insufficient configured threads: required=201 < max=200". A tunnel on a flaky network
     * reaches that in a day.
     */
    @Override
    protected void doStop() throws Exception {
        super.doStop();
        for (org.eclipse.jetty.io.SelectorManager selector
                : getBeans(org.eclipse.jetty.io.SelectorManager.class)) {
            removeBean(selector);
        }
    }

    public void setDnsResolver(CustomDnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    /** Honours --dns for the WebSocket relay's outbound connection. */
    @Override
    protected InetSocketAddress newConnectAddress(String host, int port) {
        // --connect-to decides where to dial; --dns then resolves that name. The upgrade
        // request replayed to the target still carries the original Host header.
        ConnectToMap.Target target = connectTo.remap(host, port);
        String dialHost = target.host();
        int dialPort = target.port();
        if (dnsResolver != null) {
            try {
                return new InetSocketAddress(dnsResolver.resolve(dialHost)[0], dialPort);
            } catch (java.net.UnknownHostException ex) {
                LOG.warn("Custom DNS could not resolve {}: {}", dialHost, ex.getMessage());
            }
        }
        return super.newConnectAddress(dialHost, dialPort);
    }

    public void setConnectTo(ConnectToMap connectTo) {
        this.connectTo = connectTo == null ? ConnectToMap.none() : connectTo;
    }

    public WebsocketHandler(Handler handler) {
        super(handler);
    }

    /**
     * Hop-by-hop headers must not be replayed to the target. Proxy-Authorization in
     * particular is meant for us, not the origin; the CONNECT path already strips these.
     */
    private static final java.util.Set<String> HOP_BY_HOP = java.util.Set.of(
            "proxy-authorization", "proxy-connection", "proxy-authenticate", "keep-alive", "te", "trailer");

    static String buildUpgradeRequest(Request clientRequest) {
        StringBuilder requestHeaders = new StringBuilder();
        requestHeaders.append(clientRequest.getMethod()).append(" ").append(clientRequest.getHttpURI().getPath());
        if (clientRequest.getHttpURI().getQuery() != null) {
            requestHeaders.append("?").append(clientRequest.getHttpURI().getQuery());
        }
        requestHeaders.append(" ").append(clientRequest.getConnectionMetaData().getHttpVersion().asString()).append("\r\n");
        for (HttpField field : clientRequest.getHeaders()) {
            if (HOP_BY_HOP.contains(field.getName().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            requestHeaders.append(field.getName()).append(": ").append(field.getValue()).append("\r\n");
        }
        requestHeaders.append("\r\n");
        return requestHeaders.toString();
    }

    private Map<String, String> performWebSocketHandshake(Request clientRequest, SocketChannel channel)
            throws IOException {
        // Read and write through the socket adaptor rather than the channel: SO_TIMEOUT is
        // honoured by these streams and ignored by SocketChannel.read(), so the previous code
        // could block forever on a target that accepted the connection and then said nothing.
        java.net.Socket socket = channel.socket();
        socket.setSoTimeout((int) getConnectTimeout());
        socket.getOutputStream().write(buildUpgradeRequest(clientRequest).getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();

        // One byte at a time up to the header terminator. Reading in blocks would consume
        // bytes belonging to the WebSocket stream itself -- servers that send an opening frame
        // immediately after the 101 (socket.io and friends do) had that frame silently dropped.
        java.io.InputStream in = socket.getInputStream();
        StringBuilder responseSB = new StringBuilder();
        while (responseSB.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Connection closed before WebSocket handshake completed");
            }
            responseSB.append((char) b);
            if (responseSB.length() > MAX_HANDSHAKE_BYTES) {
                throw new IOException("WebSocket handshake response exceeded " + MAX_HANDSHAKE_BYTES + " bytes");
            }
        }

        String headerSection = responseSB.substring(0, responseSB.indexOf("\r\n\r\n"));
        String[] lines = headerSection.split("\r\n");

        // Relaying a non-101 response as if it were a WebSocket stream leaves the client
        // believing the upgrade succeeded and hides the real reason (auth, redirect, refusal).
        if (lines.length == 0 || !lines[0].contains("101")) {
            throw new IOException("Target refused the WebSocket upgrade: "
                    + (lines.length > 0 ? lines[0] : "empty response"));
        }

        Map<String, String> wsResponseHeaders = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colonIndex = lines[i].indexOf(':');
            if (colonIndex > 0) {
                wsResponseHeaders.put(lines[i].substring(0, colonIndex).trim(),
                        lines[i].substring(colonIndex + 1).trim());
            }
        }

        LOG.info("WebSocket handshake with target complete, status: {}, headers: {}",
                lines[0], wsResponseHeaders.size());
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
                // newConnectAddress() applies --dns. Dialling InetSocketAddress directly here
                // is what made the custom resolver dead code on this path despite being wired.
                // socket().connect(addr, timeout) is also the only form that bounds the connect;
                // SocketChannel.connect ignores SO_TIMEOUT entirely.
                channel.socket().connect(newConnectAddress(host, port), (int) getConnectTimeout());

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
