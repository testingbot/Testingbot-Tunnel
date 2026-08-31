package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.Statistics;
import com.testingbot.tunnel.TunnelMetrics;
import io.prometheus.client.Histogram;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Custom ConnectHandler that handles proxy connections.
 */
public class CustomConnectHandler extends ConnectHandler {
    // Jetty 12's ConnectHandler no longer exposes a protected LOG to subclasses.
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CustomConnectHandler.class);

    /** Looked up once: this was a global map lookup on the request path. */
    private static final Logger JUL = Logger.getLogger(CustomConnectHandler.class.getName());

    // Hop-by-hop headers that must not be forwarded per RFC 7230 §6.1
    private static final Set<String> HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection", "host"
    )));

    // Cap on the bytes we'll read from the upstream proxy's CONNECT response before giving up
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;
    // How long an upstream proxy has to answer CONNECT. Deliberately much shorter than the idle
    // timeout the established tunnel then runs under: a tunnel may sit quiet for minutes, but a
    // proxy that has not replied to the handshake in this long is not going to.
    private static final long HANDSHAKE_TIMEOUT_MS = 15_000L;

    private boolean debugMode = false;
    private CustomDnsResolver dnsResolver;

    private final String proxyHost;
    private final int proxyPort;
    private final ProxySpec proxySpec;
    private final String proxyUserPassword;
    private ProxyAuthenticator proxyAuthenticator = ProxyAuthenticator.none();
    private FastFailPolicy fastFail = FastFailPolicy.none();
    private ConnectToMap connectTo = ConnectToMap.none();
    private LocalhostPolicy localhostPolicy = LocalhostPolicy.ALLOW;
    private com.testingbot.tunnel.pac.PacPolicy pacPolicy;

    public void setBlackList(String[] patterns) {
        this.fastFail = FastFailPolicy.compile(patterns);
    }


    public CustomConnectHandler(final App app) {
        this.proxySpec = ProxySpec.parse(app.getProxy());
        this.proxyUserPassword = app.getProxyAuth();

        if (proxySpec != null) {
            proxyHost = proxySpec.getHost();
            proxyPort = proxySpec.getPort();
        } else {
            proxyHost = null;
            proxyPort = -1;
        }

        // Proxy-Authorization only applies to an HTTP upstream proxy; SOCKS authenticates
        // inside its own handshake. Negotiate needs no user/password -- credentials come from
        // the ticket cache or keytab -- so it is configured even when --proxy-userpwd is absent.
        ProxyAuthenticator configured = app.proxyAuthenticator();
        if (proxySpec == null || !proxySpec.isSocks5()) {
            this.proxyAuthenticator = configured;
        } else {
            this.proxyAuthenticator = ProxyAuthenticator.none();
        }
    }

    public void setDnsResolver(CustomDnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    /**
     * Resolves the CONNECT target through the configured DNS server when there is one.
     * ConnectHandler calls this for every tunnel it opens, so it is the single place the
     * CONNECT path needs to honour --dns.
     */
    @Override
    protected java.net.InetSocketAddress newConnectAddress(String host, int port) {
        // --connect-to first: it decides *where* to dial, and --dns then resolves that name.
        // The CONNECT authority the client sent is untouched, so the tunnel still carries the
        // original host and the TLS handshake inside it still uses the original SNI.
        ConnectToMap.Target target = connectTo.remap(host, port);
        String dialHost = target.host();
        int dialPort = target.port();
        if (dnsResolver != null) {
            try {
                return new java.net.InetSocketAddress(dnsResolver.resolve(dialHost)[0], dialPort);
            } catch (java.net.UnknownHostException ex) {
                LOG.warn("Custom DNS could not resolve {}: {}", dialHost, ex.getMessage());
            }
        }
        return super.newConnectAddress(dialHost, dialPort);
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


    public void setPacPolicy(com.testingbot.tunnel.pac.PacPolicy pacPolicy) {
        this.pacPolicy = pacPolicy;
    }

    /**
     * The upstream proxy for this destination.
     *
     * <p>--pac-local wins over --proxy for hosts the file routes: a PAC file is per-destination
     * by definition, and a static --proxy is the fallback for everything it does not mention.
     * Only the first directive is used; the failover list would need a retry loop through this
     * hand-rolled NIO exchange to honour properly.
     */
    private ProxySpec upstreamFor(String host, int port) {
        if (pacPolicy == null) {
            return proxySpec;
        }
        com.testingbot.tunnel.pac.PacResult result =
                pacPolicy.resolve("https://" + host + ":" + port + "/", host);
        if (result.first().isDirect()) {
            return null;
        }
        return ProxySpec.parse(result.first().toProxySpec());
    }

    public void setLocalhostPolicy(LocalhostPolicy localhostPolicy) {
        this.localhostPolicy = localhostPolicy == null ? LocalhostPolicy.ALLOW : localhostPolicy;
    }

    public void setConnectTo(ConnectToMap connectTo) {
        this.connectTo = connectTo == null ? ConnectToMap.none() : connectTo;
    }

    public void setDebugMode(boolean mode) {
        debugMode = mode;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        boolean isConnect = HttpMethod.CONNECT.is(request.getMethod());
        if (isConnect) {
            // Only CONNECTs. Plain HTTP passes through here on its way to TunnelProxyHandler,
            // which counts it on completion; counting in both places doubled the request total
            // reported by the status endpoint.
            Statistics.addRequest();
        }
        // No per-CONNECT line here. HttpLogHandler sits outermost and logs every request,
        // CONNECT included, under the --log-http switch. This one ignored that switch entirely,
        // so "--log-http none" still produced a line per tunnelled connection -- and did a
        // Logger.getLogger() lookup on the request path to produce it.

        if (debugMode) {
            StringBuilder sb = new StringBuilder();
            for (HttpField field : request.getHeaders()) {
                sb.append(field.getName()).append(": ")
                        .append(SensitiveHeaders.redactValue(field.getName(), field.getValue()))
                        .append(System.lineSeparator());
            }
            JUL.log(Level.INFO, sb.toString());
        }

        if (!isConnect) {
            return super.handle(request, response, callback);
        }

        // CONNECT is handled asynchronously in Jetty 12: super.handle() can return before the
        // tunnel is established, so record timing and status when the callback completes rather
        // than immediately after the call.
        Histogram.Timer connectTimer = TunnelMetrics.HTTPS_CONNECT_DURATION_SECONDS.startTimer();
        Callback observed = Callback.from(callback, () -> {
            connectTimer.observeDuration();
            int status = response.getStatus();
            TunnelMetrics.HTTPS_CONNECT_TOTAL.labels(Integer.toString(status)).inc();
            // Deliberately no error increment here. Failures are already counted once, with a
            // classified reason, in onConnectFailure/validateDestination; adding a status_* label
            // for the same event made every failure count two or three times under parallel
            // taxonomies and inflated any sum() over the family.
        });

        boolean handled = super.handle(request, response, observed);
        if (!handled) {
            connectTimer.observeDuration();
        }
        return handled;
    }

    /**
     * Fast-fail policy hook. {@link ConnectHandler#handleConnect} calls this before opening a
     * connection and answers 403 itself when it returns false, so the blacklist lives here
     * rather than being intercepted earlier in {@link #handle}.
     */
    /**
     * Classifies CONNECT failures the same way the HTTP path does.
     *
     * <p>Runs before the tunnel is established, so the response is still an ordinary HTTP
     * message and can carry the reason header. Without this, every CONNECT failure -- DNS,
     * refusal, a dead upstream proxy, rejected proxy credentials -- looked identical.
     */
    @Override
    protected void onConnectFailure(Request request, Response response, Callback callback,
                                    Throwable failure) {
        if (proxyHost == null) {
            TunnelMetrics.DIAL_TOTAL.labels("connect", "failure").inc();
            observeDial(request);
        }
        ProxyErrors.Reason reason = ProxyErrors.classify(failure);
        TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels(reason.reason().replace('-', '_')).inc();
        LOG.warn("CONNECT failed ({}): {}", reason.reason(),
                failure == null ? "unknown" : failure.getMessage());
        ProxyErrors.write(request, response, callback, reason);
    }

    private static final String ATTR_DIAL_TIMER = CustomConnectHandler.class.getName() + ".dialTimer";

    /** Stops the direct-path dial timer once, whichever way the dial resolved. */
    private static void observeDial(Request request) {
        if (request == null) {
            return;
        }
        Object timer = request.removeAttribute(ATTR_DIAL_TIMER);
        if (timer instanceof io.prometheus.client.Histogram.Timer t) {
            t.observeDuration();
        }
    }

    @Override
    protected void onConnectSuccess(ConnectContext connectContext, UpstreamConnection upstreamConnection) {
        if (proxyHost == null) {
            TunnelMetrics.DIAL_TOTAL.labels("connect", "success").inc();
            observeDial(connectContext.getRequest());
        }
        super.onConnectSuccess(connectContext, upstreamConnection);
    }

    @Override
    public boolean validateDestination(String host, int port) {
        if (localhostPolicy.blocks(host, dnsResolver == null ? null : dnsResolver::resolve)) {
            JUL.log(Level.INFO, "Localhost policy: rejecting CONNECT to {0}:{1}",
                     new Object[]{host, port});
            TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels("denied_localhost").inc();
            return false;
        }
        if (fastFail.blocks(host)) {
            JUL.log(Level.INFO, "Fast-fail: rejecting CONNECT to {0}:{1} (matched blacklist)",
                     new Object[]{host, port});
            TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels("blacklisted").inc();
            return false;
        }
        return super.validateDestination(host, port);
    }

    @Override
    protected void connectToServer(Request request, String host, int port, Promise<SocketChannel> promise) {
        // Resolved per destination: with --pac-local the answer differs by host, and without it
        // this is just the static --proxy.
        ProxySpec upstream = upstreamFor(host, port);
        if (upstream == null) {
            // Not wrapped in timedDial: Jetty's default connectToServer succeeds the promise as
            // soon as the non-blocking connect is *initiated*, so wrapping it recorded every
            // dial as an instant success and never saw a refusal. Start the clock here and stop
            // it in onConnectSuccess/onConnectFailure, where the outcome is actually known.
            request.setAttribute(ATTR_DIAL_TIMER,
                    TunnelMetrics.DIAL_DURATION_SECONDS.labels("connect").startTimer());
            super.connectToServer(request, host, port, promise);
            return;
        }
        if (upstream.isSocks5()) {
            connectViaSocks5(upstream, host, port,
                    TunnelMetrics.timedDial("connect", promise));
            return;
        }

        // Dial the proxy the same way Jetty dials a direct target: open the channel, start a
        // non-blocking connect and hand it back. Jetty's selector takes it from there, and the
        // CONNECT exchange runs in ProxyHandshakeConnection when the socket comes up.
        //
        // This replaced a private Selector and a select loop that ran inline on the Jetty request
        // thread for up to fifteen seconds. With a slow upstream proxy, enough concurrent
        // CONNECTs would occupy the whole pool and stall unrelated requests.
        //
        // The outcome is counted in ProxyHandshakeConnection, which is where it is known: a TCP
        // connection to the proxy is not a usable tunnel until the proxy has agreed to it.
        request.setAttribute(ATTR_PROXY_TARGET_REQUEST, new ProxyTarget(upstream, host, port));
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            channel.connect(newConnectAddress(upstream.getHost(), upstream.getPort()));
            promise.succeeded(channel);
        } catch (Throwable x) {
            closeQuietly(channel);
            LOG.error("Could not dial upstream proxy {}:{}: {}",
                    upstream.getHost(), upstream.getPort(), x.getMessage());
            TunnelMetrics.DIAL_TOTAL.labels("connect", "failure").inc();
            promise.failed(x);
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // already failing; nothing useful to do
            }
        }
    }

    /**
     * Opens the tunnel through a SOCKS5 upstream proxy. The handshake is short and blocking,
     * so it runs on the executor rather than being woven into the selector loop the HTTP
     * CONNECT path uses; the channel is switched back to non-blocking before Jetty gets it.
     */
    private void connectViaSocks5(ProxySpec upstream, String host, int port,
                                  Promise<SocketChannel> promise) {
        getExecutor().execute(() -> {
            SocketChannel channel = null;
            SocketChannel prepared;
            try {
                channel = SocketChannel.open();
                channel.socket().setTcpNoDelay(true);
                channel.socket().connect(newConnectAddress(upstream.getHost(), upstream.getPort()), (int) getConnectTimeout());
                // Bounds the handshake reads too, not just the TCP connect: without this a
                // SOCKS proxy that stalls mid-handshake pins this thread and the CONNECT
                // callback is never completed, so the client request hangs forever.
                channel.socket().setSoTimeout((int) getConnectTimeout());

                String[] credentials = TunnelProxyHandler.splitCredentials(proxyUserPassword);
                Socks5Client.connect(channel, host, port,
                        credentials == null ? null : credentials[0],
                        credentials == null ? null : credentials[1]);

                channel.configureBlocking(false);
                if (debugMode) {
                    LOG.info("Established SOCKS5 tunnel through {}:{} to {}:{}",
                            upstream.getHost(), upstream.getPort(), host, port);
                }
                prepared = channel;
            } catch (Throwable x) {
                LOG.error("Failed to establish SOCKS5 tunnel through {}:{} to {}:{}: {}",
                        upstream.getHost(), upstream.getPort(), host, port, x.getMessage());
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // already failing; nothing useful to do
                    }
                }
                promise.failed(x);
            }
        });
    }


    /**
     * The CONNECT request sent to an upstream HTTP proxy.
     *
     * <p>Client headers are replayed except hop-by-hop ones, which belong to our hop rather than
     * the proxy's, and any value containing CR or LF, which would let a client inject headers
     * into a request we are making on its behalf.
     */
    /**
     * Carries the destination for a dial that has to traverse an upstream HTTP proxy, so the
     * handshake connection knows what to ask for once the socket is up.
     */
    private static final String ATTR_PROXY_TARGET_REQUEST =
            CustomConnectHandler.class.getName() + ".proxyTarget";

    /** What the CONNECT to the upstream proxy must ask for. */
    private record ProxyTarget(ProxySpec upstream, String host, int port) {
    }

    /**
     * Performs the upstream proxy's CONNECT exchange on Jetty's selector rather than on a thread.
     *
     * <p>The previous implementation opened its own Selector and ran a select loop inline on the
     * Jetty request thread for up to fifteen seconds. With an upstream proxy that is slow to
     * answer, enough concurrent CONNECTs would occupy the whole pool and stall unrelated
     * requests.
     *
     * <p>The tunnel is only published to the client once the proxy has agreed to it:
     * {@code super.onOpen()} is what calls {@code onConnectSuccess}, so it is deliberately
     * deferred until the handshake completes. AbstractConnection.onOpen only notifies listeners,
     * so delaying it is safe.
     */
    private class ProxyHandshakeConnection extends UpstreamConnection {

        private final ConnectContext context;
        private final ProxyTarget target;
        private final StringBuilder response = new StringBuilder();
        // Jetty's fill() appends to a buffer in flush mode and takes its space from limit to
        // capacity. A ByteBuffer.allocate() is in fill mode, where limit == capacity, so fill()
        // sees no room and reads nothing -- which spins the selector rather than failing.
        private final ByteBuffer readBuffer = org.eclipse.jetty.util.BufferUtil.allocate(1);
        private boolean handshakeDone;

        ProxyHandshakeConnection(EndPoint endPoint, ConnectContext context, ProxyTarget target) {
            super(endPoint, CustomConnectHandler.this.getExecutor(),
                    CustomConnectHandler.this.getByteBufferPool(), context);
            this.context = context;
            this.target = target;
        }

        @Override
        public void onOpen() {
            // The endpoint was created with the tunnel's idle timeout, which is long by design.
            // The handshake gets its own, shorter one, restored once the tunnel is up.
            getEndPoint().setIdleTimeout(HANDSHAKE_TIMEOUT_MS);
            String request = connectRequest(target.upstream(), context.getRequest(),
                    target.host(), target.port());
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII));
            getEndPoint().write(Callback.from(this::readMore, this::handshakeFailed), buffer);
        }

        private void readMore() {
            getEndPoint().fillInterested(Callback.from(this::onResponseBytes, this::handshakeFailed));
        }

        /**
         * Reads the reply one byte at a time, stopping at the header terminator.
         *
         * <p>Anything the proxy pipelines after its response belongs to the tunnelled stream. A
         * block read would take those bytes off the socket and drop them, and the TLS handshake
         * inside would fail with nothing to explain it.
         */
        private void onResponseBytes() {
            try {
                while (response.indexOf("\r\n\r\n") < 0) {
                    org.eclipse.jetty.util.BufferUtil.clear(readBuffer);
                    int read = getEndPoint().fill(readBuffer);
                    if (read < 0) {
                        throw new IOException("Upstream proxy " + target.upstream().getHost() + ":"
                                + target.upstream().getPort()
                                + " closed the connection before answering CONNECT");
                    }
                    if (read == 0) {
                        readMore();
                        return;
                    }
                    response.append((char) (readBuffer.get() & 0xFF));
                    if (response.length() > MAX_RESPONSE_BYTES) {
                        throw new IOException("Upstream proxy response exceeded "
                                + MAX_RESPONSE_BYTES + " bytes");
                    }
                }

                String statusLine = response.substring(0, response.indexOf("\r\n"));
                if (!isSuccessfulConnect(statusLine)) {
                    throw new IOException(String.format(
                            "Upstream proxy (%s:%d) rejected CONNECT to %s:%d. Status: %s",
                            target.upstream().getHost(), target.upstream().getPort(),
                            target.host(), target.port(), statusLine));
                }

                if (debugMode) {
                    LOG.info("Established CONNECT tunnel through upstream proxy {}:{} to {}:{}",
                            target.upstream().getHost(), target.upstream().getPort(),
                            target.host(), target.port());
                }
                handshakeDone = true;
                getEndPoint().setIdleTimeout(getIdleTimeout());
                TunnelMetrics.DIAL_TOTAL.labels("connect", "success").inc();
                // Publishes the tunnel: this is what calls onConnectSuccess.
                super.onOpen();
            } catch (Throwable failure) {
                handshakeFailed(failure);
            }
        }

        private void handshakeFailed(Throwable failure) {
            if (handshakeDone) {
                // Past the hand-off; the tunnel owns its own failures now.
                return;
            }
            handshakeDone = true;
            TunnelMetrics.DIAL_TOTAL.labels("connect", "failure").inc();
            LOG.error("Failed to establish CONNECT tunnel through upstream proxy {}:{} to {}:{}: {}",
                    target.upstream().getHost(), target.upstream().getPort(),
                    target.host(), target.port(), failure.getMessage());
            onConnectFailure(context.getRequest(), context.getResponse(), context.getCallback(),
                    failure);
            getEndPoint().close();
        }

        @Override
        public boolean onIdleExpired(java.util.concurrent.TimeoutException timeout) {
            if (!handshakeDone) {
                handshakeFailed(new IOException("Timed out waiting for upstream proxy "
                        + target.upstream().getHost() + ":" + target.upstream().getPort()
                        + " to answer CONNECT"));
                return false;
            }
            return super.onIdleExpired(timeout);
        }
    }

    /**
     * Returns the handshake connection when the dial has to traverse an upstream HTTP proxy, and
     * Jetty's ordinary one otherwise.
     */
    @Override
    protected UpstreamConnection newUpstreamConnection(EndPoint endPoint, ConnectContext context) {
        Object target = context.getRequest().getAttribute(ATTR_PROXY_TARGET_REQUEST);
        if (target instanceof ProxyTarget proxyTarget) {
            return new ProxyHandshakeConnection(endPoint, context, proxyTarget);
        }
        return super.newUpstreamConnection(endPoint, context);
    }

    String connectRequest(ProxySpec upstream, Request request, String host, int port) {
        final StringBuilder connect = new StringBuilder();
        connect.append("CONNECT ").append(host).append(':').append(port)
                .append(' ').append(request.getConnectionMetaData().getHttpVersion().asString()).append("\r\n");
        connect.append("Host: ").append(host).append(':').append(port).append("\r\n");

        for (HttpField field : request.getHeaders()) {
            final String headerName = field.getName();
            if (HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            final String headerValue = field.getValue();
            if (headerValue == null) {
                continue;
            }
            if (headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                continue;
            }
            connect.append(headerName).append(": ").append(headerValue).append("\r\n");
        }

        // Sent pre-emptively rather than after a 407: a proxy that does not want it ignores it,
        // and waiting for the challenge would mean replaying the whole exchange.
        String authorization = proxyAuthenticator.authorizationValue(upstream.getHost());
        if (authorization != null) {
            connect.append("Proxy-Authorization: ").append(authorization).append("\r\n");
        }

        connect.append("\r\n");
        return connect.toString();
    }

    // Parse "HTTP/1.x NNN reason" and return true iff NNN is in [200, 299].
    static boolean isSuccessfulConnect(String statusLine) {
        if (statusLine == null) return false;
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) return false;
        if (!parts[0].startsWith("HTTP/")) return false;
        try {
            int code = Integer.parseInt(parts[1]);
            return code >= 200 && code < 300;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
