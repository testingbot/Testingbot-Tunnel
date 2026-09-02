package com.testingbot.tunnel.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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
    private AllowedHosts allowedHosts = AllowedHosts.unrestricted();
    private FastFailPolicy fastFail = FastFailPolicy.none();
    private LocalhostPolicy localhostPolicy = LocalhostPolicy.ALLOW;
    /**
     * Egress routing, held to the same shape as CustomConnectHandler's.
     *
     * <p>Without these this handler dialled the target directly whatever {@code --proxy} said,
     * so on a network whose only way out is a proxy, {@code http://} and {@code https://} worked
     * and {@code ws://} hung until the dial timed out. It sits outside CustomConnectHandler in
     * the chain, so nothing downstream could apply them on its behalf.
     */
    private ProxySpec proxySpec;
    private com.testingbot.tunnel.pac.PacPolicy pacPolicy;
    private ProxyAuthenticator proxyAuthenticator = ProxyAuthenticator.none();
    private String proxyUserPassword;

    /** The static {@code --proxy}, or null for a direct dial. */
    public void setProxySpec(ProxySpec proxySpec) {
        this.proxySpec = proxySpec;
    }

    public void setPacPolicy(com.testingbot.tunnel.pac.PacPolicy pacPolicy) {
        this.pacPolicy = pacPolicy;
    }

    /**
     * How to authenticate to the upstream proxy.
     *
     * <p>SOCKS5 authenticates inside its own handshake, from {@code --proxy-userpwd}, so an HTTP
     * authenticator is not applied to it -- the same rule CustomConnectHandler follows.
     */
    public void setProxyAuthenticator(ProxyAuthenticator proxyAuthenticator) {
        this.proxyAuthenticator = proxyAuthenticator == null
                ? ProxyAuthenticator.none() : proxyAuthenticator;
    }

    public void setProxyUserPassword(String proxyUserPassword) {
        this.proxyUserPassword = proxyUserPassword;
    }

    /**
     * The proxy to route this destination through, or null to dial it directly.
     *
     * <p>Resolved per destination because {@code --pac-local} can answer differently per host;
     * without a PAC file this is just the static {@code --proxy}.
     */
    ProxySpec upstreamFor(String host, int port) {
        if (pacPolicy == null) {
            return proxySpec;
        }
        // ws:// is carried over HTTP, so that is the scheme a PAC file is asked about.
        com.testingbot.tunnel.pac.PacResult result =
                pacPolicy.resolve("http://" + host + ":" + port + "/", host);
        if (result.first().isDirect()) {
            return null;
        }
        return ProxySpec.parse(result.first().toProxySpec());
    }

    public void setAllowedHosts(AllowedHosts allowedHosts) {
        this.allowedHosts = allowedHosts == null ? AllowedHosts.unrestricted() : allowedHosts;
    }

    public void setBlackList(String[] patterns) {
        this.fastFail = FastFailPolicy.compile(patterns);
    }

    public void setLocalhostPolicy(LocalhostPolicy localhostPolicy) {
        this.localhostPolicy = localhostPolicy == null ? LocalhostPolicy.ALLOW : localhostPolicy;
    }

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
        return buildUpgradeRequest(clientRequest, false, null);
    }

    /**
     * The upgrade to replay against the target.
     *
     * @param absoluteForm  true when this goes to an HTTP proxy rather than the target itself, so
     *                      the request line names the whole URL. An upgrade needs no CONNECT --
     *                      a proxy forwards it like any other request and relays the 101 back
     * @param authorization the {@code Proxy-Authorization} value, or null
     */
    static String buildUpgradeRequest(Request clientRequest, boolean absoluteForm,
            String authorization) {
        StringBuilder requestHeaders = new StringBuilder();
        requestHeaders.append(clientRequest.getMethod()).append(" ");
        if (absoluteForm) {
            requestHeaders.append("http://").append(clientRequest.getHttpURI().getAuthority());
        }
        requestHeaders.append(clientRequest.getHttpURI().getPath());
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
        // Sent pre-emptively rather than after a 407, as the CONNECT path does: waiting for the
        // challenge would mean replaying the upgrade, and an upgrade is not replayable.
        if (authorization != null) {
            requestHeaders.append("Proxy-Authorization: ").append(authorization).append("\r\n");
        }
        requestHeaders.append("\r\n");
        return requestHeaders.toString();
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
            // This handler sits outside CustomConnectHandler in the chain and intercepts
            // upgrades before they reach it, so none of the destination policies were being
            // applied to a WebSocket at all: --fast-fail-regexps and --localhost-policy deny
            // could both be walked straight past by using ws:// instead of http://.
            String target = request.getHttpURI().getHost();
            if (!allowedHosts.permits(target)) {
                LOG.info("Not in --allow-hosts: rejecting WebSocket upgrade to {}", target);
                ProxyErrors.write(request, response, callback, ProxyErrors.Reason.NOT_ALLOWED);
                return true;
            }
            if (fastFail.blocks(target)) {
                LOG.info("Fast-fail: rejecting WebSocket upgrade to {}", target);
                ProxyErrors.write(request, response, callback,
                        ProxyErrors.Reason.DENIED_BY_FAST_FAIL);
                return true;
            }
            if (localhostPolicy.blocks(target, dnsResolver == null ? null : dnsResolver::resolve)) {
                LOG.info("Localhost policy: rejecting WebSocket upgrade to {}", target);
                ProxyErrors.write(request, response, callback,
                        ProxyErrors.Reason.DENIED_LOCALHOST);
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

    /** Carries the upgrade target from connectToServer to the handshake connection. */
    private static final String WS_TARGET_ATTRIBUTE =
            WebsocketHandler.class.getName() + ".upgradeTarget";

    /**
     * Starts a non-blocking connect and hands the channel to Jetty; the upgrade handshake runs
     * on the selector once the socket is up.
     *
     * <p>This used to do the connect and the whole handshake on an executor thread. That was the
     * last of three dial paths still working that way -- the CONNECT and SOCKS5 paths were moved
     * to the selector earlier -- and while a bounded connect timeout kept it from being as bad
     * as the fifteen-second select loop that preceded it, enough concurrent upgrades to a slow
     * target still tied up pool threads doing nothing but waiting.
     */
    /** Carries the upstream proxy and the real destination to the handshake connection. */
    private static final String WS_PROXY_TARGET_ATTRIBUTE =
            WebsocketHandler.class.getName() + ".proxyTarget";

    /** What the handshake has to ask the upstream proxy for. */
    private record ProxyTarget(ProxySpec upstream, String host, int port) {
    }

    @Override
    protected void connectToServer(Request request, String host, int port, Promise<SocketChannel> promise) {
        request.setAttribute(WS_TARGET_ATTRIBUTE, Boolean.TRUE);
        ProxySpec upstream = upstreamFor(host, port);
        if (upstream != null) {
            request.setAttribute(WS_PROXY_TARGET_ATTRIBUTE, new ProxyTarget(upstream, host, port));
        }
        request.setAttribute(WS_DIAL_TIMER_ATTRIBUTE,
                com.testingbot.tunnel.TunnelMetrics.DIAL_DURATION_SECONDS
                        .labels("websocket").startTimer());
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            // newConnectAddress() applies --dns and --connect-to. Dialling an InetSocketAddress
            // directly here is what once made the custom resolver dead code on this path.
            //
            // With an upstream proxy the socket goes to the proxy; the target's name travels in
            // the request line, which is why --connect-to and --dns are not applied to it here.
            channel.connect(upstream == null
                    ? newConnectAddress(host, port)
                    : newConnectAddress(upstream.getHost(), upstream.getPort()));
            promise.succeeded(channel);
        } catch (Throwable x) {
            close(channel);
            LOG.warn("WebSocket connect to {}:{} failed", upstream == null ? host
                    : upstream.getHost(), upstream == null ? port : upstream.getPort(), x);
            // Not counted here: the promise failure reaches onConnectFailure, which counts it.
            promise.failed(x);
        }
    }

    private static final String WS_DIAL_TIMER_ATTRIBUTE =
            WebsocketHandler.class.getName() + ".dialTimer";

    /** Stops the dial timer once, whichever way the dial resolved. */
    private static void observeWsDial(Request request) {
        if (request == null) {
            return;
        }
        Object timer = request.removeAttribute(WS_DIAL_TIMER_ATTRIBUTE);
        if (timer instanceof io.prometheus.client.Histogram.Timer t) {
            t.observeDuration();
        }
    }

    /**
     * Tells the client why an upgrade could not be dialled.
     *
     * <p>Without this override the failure went to ConnectHandler's default, which calls
     * sendConnectResponse -- setting Content-Length: 0 and then writing an error body. The
     * client got either nothing at all or a 500 naming Jetty's own content-length bookkeeping,
     * while a CONNECT to the same unreachable host answered 502 with a classified reason. "The
     * service behind the tunnel is down" and "the tunnel is broken" were indistinguishable.
     *
     * <p>The dial is also counted here, which is where a TCP-level failure actually arrives:
     * ConnectManager.connectionFailed calls this directly, so the handshake connection that
     * counts the other outcomes never exists.
     */
    @Override
    protected void onConnectFailure(Request request, Response response, Callback callback,
                                    Throwable failure) {
        com.testingbot.tunnel.TunnelMetrics.DIAL_TOTAL.labels("websocket", "failure").inc();
        observeWsDial(request);
        ProxyErrors.Reason reason = ProxyErrors.classify(failure);
        LOG.warn("WebSocket upgrade failed ({}): {}", reason.reason(),
                failure == null ? "unknown" : failure.getMessage());
        ProxyErrors.write(request, response, callback, reason);
    }

    @Override
    protected UpstreamConnection newUpstreamConnection(EndPoint endPoint, ConnectContext context) {
        if (context.getRequest().getAttribute(WS_TARGET_ATTRIBUTE) != null) {
            Object target = context.getRequest().getAttribute(WS_PROXY_TARGET_ATTRIBUTE);
            return new WebsocketHandshakeConnection(endPoint, context,
                    target instanceof ProxyTarget proxyTarget ? proxyTarget : null);
        }
        return super.newUpstreamConnection(endPoint, context);
    }

    /**
     * Replays the upgrade against the target on the selector, and only publishes the tunnel once
     * the target has answered 101.
     *
     * <p>{@code super.onOpen()} is what calls {@code onConnectSuccess}, which answers the client
     * with 101 and the target's headers -- so it is deliberately deferred until those headers
     * exist. AbstractConnection.onOpen only notifies listeners, so delaying it is safe.
     */
    private class WebsocketHandshakeConnection extends UpstreamConnection {

        private final ConnectContext context;
        /** The upstream proxy this has to traverse, or null for a direct dial. */
        private final ProxyTarget proxyTarget;
        private final Socks5Handshake socks;
        private ByteBuffer socksBuffer;
        private final StringBuilder response = new StringBuilder();
        // Jetty's fill() appends to a buffer in flush mode, taking its space from limit to
        // capacity. A ByteBuffer.allocate() is in fill mode, where limit == capacity, so fill()
        // would see no room and read nothing -- spinning the selector rather than failing.
        private final ByteBuffer readBuffer = org.eclipse.jetty.util.BufferUtil.allocate(1);
        /**
         * Written on the selector thread, read on the scheduler thread: IdleTimeout schedules
         * its check on the Scheduler and AbstractEndPoint calls onIdleExpired inline there. A
         * plain boolean let an idle expiry landing inside the hand-off window count one dial as
         * both success and failure -- writing a 502 for a tunnel that was granted, or tearing
         * down one the client had already been told about.
         */
        private final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean();

        WebsocketHandshakeConnection(EndPoint endPoint, ConnectContext context,
                ProxyTarget proxyTarget) {
            super(endPoint, WebsocketHandler.this.getExecutor(),
                    WebsocketHandler.this.getByteBufferPool(), context);
            this.context = context;
            this.proxyTarget = proxyTarget;
            if (proxyTarget != null && proxyTarget.upstream().isSocks5()) {
                String[] credentials = TunnelProxyHandler.splitCredentials(proxyUserPassword);
                this.socks = new Socks5Handshake(proxyTarget.host(), proxyTarget.port(),
                        credentials == null ? null : credentials[0],
                        credentials == null ? null : credentials[1]);
            } else {
                this.socks = null;
            }
        }

        @Override
        public void onOpen() {
            // The endpoint carries the tunnel's idle timeout, which is long by design. The
            // handshake gets the connect timeout instead, restored once the tunnel is up.
            getEndPoint().setIdleTimeout(getConnectTimeout());
            if (socks != null) {
                // SOCKS5 negotiates before anything HTTP can be sent; the upgrade follows once
                // the proxy has opened the connection to the target.
                sendSocks(socks.greeting());
                return;
            }
            sendUpgrade();
        }

        private void sendUpgrade() {
            // Through an HTTP proxy the upgrade goes in absolute form. No CONNECT: a proxy
            // forwards an upgrade like any other request and relays the 101 back, so the reply
            // reader below is the same either way.
            boolean throughHttpProxy = proxyTarget != null && !proxyTarget.upstream().isSocks5();
            String authorization = throughHttpProxy
                    ? proxyAuthenticator.authorizationValue(proxyTarget.upstream().getHost())
                    : null;
            ByteBuffer request = ByteBuffer.wrap(
                    buildUpgradeRequest(context.getRequest(), throughHttpProxy, authorization)
                            .getBytes(StandardCharsets.UTF_8));
            getEndPoint().write(Callback.from(this::readMore, this::handshakeFailed), request);
        }

        private void sendSocks(ByteBuffer buffer) {
            getEndPoint().write(Callback.from(this::awaitSocksStep, this::handshakeFailed), buffer);
        }

        private void awaitSocksStep() {
            // Sized to exactly what the step wants, so a read can never take a byte of the
            // upgrade reply that follows it.
            socksBuffer = org.eclipse.jetty.util.BufferUtil.allocate(socks.bytesNeeded());
            getEndPoint().fillInterested(
                    Callback.from(this::onSocksBytes, this::handshakeFailed));
        }

        private void onSocksBytes() {
            try {
                while (true) {
                    int wanted = socks.bytesNeeded();
                    while (org.eclipse.jetty.util.BufferUtil.length(socksBuffer) < wanted) {
                        int read = getEndPoint().fill(socksBuffer);
                        if (read < 0) {
                            throw new IOException("Upstream SOCKS proxy "
                                    + proxyTarget.upstream().getHost() + ":"
                                    + proxyTarget.upstream().getPort()
                                    + " closed the connection during the handshake");
                        }
                        if (read == 0) {
                            getEndPoint().fillInterested(
                                    Callback.from(this::onSocksBytes, this::handshakeFailed));
                            return;
                        }
                    }
                    ByteBuffer reply = socks.accept(socksBuffer);
                    if (socks.isDone()) {
                        sendUpgrade();
                        return;
                    }
                    if (reply != null) {
                        sendSocks(reply);
                        return;
                    }
                    socksBuffer = org.eclipse.jetty.util.BufferUtil.allocate(socks.bytesNeeded());
                }
            } catch (Throwable failure) {
                handshakeFailed(failure);
            }
        }

        private void readMore() {
            getEndPoint().fillInterested(Callback.from(this::onResponseBytes, this::handshakeFailed));
        }

        /**
         * Reads the reply a byte at a time, stopping at the header terminator.
         *
         * <p>Anything the target sends after its 101 is already WebSocket frames. A block read
         * would take those off the socket and drop them, and socket.io in particular sends its
         * first frame immediately.
         */
        private void onResponseBytes() {
            try {
                while (response.indexOf("\r\n\r\n") < 0) {
                    org.eclipse.jetty.util.BufferUtil.clear(readBuffer);
                    int read = getEndPoint().fill(readBuffer);
                    if (read < 0) {
                        throw new IOException(
                                "Connection closed before the WebSocket upgrade was answered");
                    }
                    if (read == 0) {
                        readMore();
                        return;
                    }
                    response.append((char) (readBuffer.get() & 0xFF));
                    if (response.length() > MAX_HANDSHAKE_BYTES) {
                        throw new IOException("WebSocket handshake response exceeded "
                                + MAX_HANDSHAKE_BYTES + " bytes");
                    }
                }

                String[] lines = response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n");
                if (lines.length == 0 || !lines[0].contains("101")) {
                    throw new IOException("Target refused WebSocket upgrade: "
                            + (lines.length > 0 ? lines[0] : "empty response"));
                }
                // add(), not put() into a Map: a 101 carrying two Set-Cookie -- a session and a
                // load balancer's affinity cookie is ordinary -- reached the client with only
                // the last of them.
                org.eclipse.jetty.http.HttpFields.Mutable wsResponseHeaders =
                        org.eclipse.jetty.http.HttpFields.build();
                for (int i = 1; i < lines.length; i++) {
                    int colonIndex = lines[i].indexOf(':');
                    if (colonIndex > 0) {
                        wsResponseHeaders.add(lines[i].substring(0, colonIndex).trim(),
                                lines[i].substring(colonIndex + 1).trim());
                    }
                }
                context.getRequest().setAttribute(WS_RESPONSE_HEADERS_ATTRIBUTE, wsResponseHeaders);
                handshakeSucceeded(lines[0], wsResponseHeaders.size());
            } catch (Throwable failure) {
                handshakeFailed(failure);
            }
        }

        /** Named to match CustomConnectHandler's, so the two can be compared side by side. */
        private void handshakeSucceeded(String statusLine, int headerCount) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            getEndPoint().setIdleTimeout(getIdleTimeout());
            com.testingbot.tunnel.TunnelMetrics.DIAL_TOTAL.labels("websocket", "success").inc();
            observeWsDial(context.getRequest());
            LOG.info("WebSocket handshake with target complete, status: {}, headers: {}",
                    statusLine, headerCount);
            // Publishes the tunnel: this is what calls onConnectSuccess.
            super.onOpen();
        }

        private void handshakeFailed(Throwable failure) {
            if (!settled.compareAndSet(false, true)) {
                // Already settled -- past the hand-off, or a concurrent failure won.
                return;
            }
            // Counting and reporting both live in onConnectFailure, so this must not repeat
            // them: doing it in two places counted one failed dial twice.
            onConnectFailure(context.getRequest(), context.getResponse(), context.getCallback(),
                    failure);
            getEndPoint().close();
        }

        @Override
        public boolean onIdleExpired(java.util.concurrent.TimeoutException timeout) {
            if (!settled.get()) {
                handshakeFailed(new IOException(
                        "Timed out waiting for the target to answer the WebSocket upgrade"));
                return false;
            }
            return super.onIdleExpired(timeout);
        }
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
            Object wsResponseHeaders = request.getAttribute(WS_RESPONSE_HEADERS_ATTRIBUTE);
            if (wsResponseHeaders instanceof org.eclipse.jetty.http.HttpFields fields) {
                for (HttpField field : fields) {
                    response.getHeaders().add(field);
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
