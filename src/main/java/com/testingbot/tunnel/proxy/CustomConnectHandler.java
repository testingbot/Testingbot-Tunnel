package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.Statistics;
import com.testingbot.tunnel.TunnelMetrics;
import io.prometheus.client.Histogram;
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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
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

    // Hop-by-hop headers that must not be forwarded per RFC 7230 §6.1
    private static final Set<String> HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection", "host"
    )));

    // Cap on the bytes we'll read from the upstream proxy's CONNECT response before giving up
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;
    // selector.select() timeout per iteration
    private static final long SELECT_TIMEOUT_MS = 15_000L;

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
        if (isConnect) {
            // For CONNECT, the authority ("host:port") lives on the parsed HttpURI;
            // the path component alone would not carry it.
            String authority = request.getHttpURI().getAuthority();
            if (authority == null || authority.isEmpty()) {
                authority = request.getHeaders().get(HttpHeader.HOST);
            }
            // Blocked destinations are rejected in validateDestination(), which
            // ConnectHandler already consults before dialling out.
            Logger.getLogger(CustomConnectHandler.class.getName()).log(Level.INFO,
                    "[CONNECT] {0} -> {1}",
                    new Object[]{Request.getRemoteAddr(request), authority != null ? authority : "<unknown>"});
        }

        if (debugMode) {
            StringBuilder sb = new StringBuilder();
            for (HttpField field : request.getHeaders()) {
                sb.append(field.getName()).append(": ")
                        .append(SensitiveHeaders.redactValue(field.getName(), field.getValue()))
                        .append(System.lineSeparator());
            }
            Logger.getLogger(CustomConnectHandler.class.getName()).log(Level.INFO, sb.toString());
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
        if (localhostPolicy.blocks(host)) {
            Logger.getLogger(CustomConnectHandler.class.getName())
                .log(Level.INFO, "Localhost policy: rejecting CONNECT to {0}:{1}",
                     new Object[]{host, port});
            TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels("denied_localhost").inc();
            return false;
        }
        if (fastFail.blocks(host)) {
            Logger.getLogger(CustomConnectHandler.class.getName())
                .log(Level.INFO, "Fast-fail: rejecting CONNECT to {0}:{1} (matched blacklist)",
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
        Promise<SocketChannel> timed = TunnelMetrics.timedDial("connect", promise);
        if (upstream.isSocks5()) {
            connectViaSocks5(upstream, host, port, timed);
        } else {
            connectToProxy(upstream, request, host, port, timed);
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
                promise.succeeded(channel);
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

    private void connectToProxy(ProxySpec upstream, Request request, String host, int port,
                                Promise<SocketChannel> promise) {
        SocketChannel channel = null;
        Selector selector = null;
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            channel.connect(newConnectAddress(upstream.getHost(), upstream.getPort()));

            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_CONNECT);

            final long deadline = System.currentTimeMillis() + SELECT_TIMEOUT_MS;
            StringBuilder responseBuf = null;
            int totalRead = 0;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IOException("Timed out waiting for upstream proxy " + upstream.getHost() + ":" + upstream.getPort());
                }
                int ready = selector.select(remaining);
                if (ready == 0) {
                    // hit selector deadline, loop and recheck
                    continue;
                }
                final Set<SelectionKey> keys = selector.selectedKeys();
                final Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    final SelectionKey key = iterator.next();
                    iterator.remove();

                    if (key.isConnectable()) {
                        if (!channel.finishConnect()) {
                            throw new IOException("finishConnect() returned false for " + upstream.getHost() + ":" + upstream.getPort());
                        }

                        channel.register(selector, SelectionKey.OP_READ);

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
                            if (headerValue == null) continue;
                            // Drop any value containing CR/LF defensively (header smuggling)
                            if (headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                                continue;
                            }
                            connect.append(headerName).append(": ").append(headerValue).append("\r\n");
                        }

                        // Sent pre-emptively rather than after a 407. A proxy that does not
                        // want it ignores it, and waiting for the challenge would mean
                        // replaying the CONNECT over this hand-rolled NIO exchange.
                        String authorization = proxyAuthenticator.authorizationValue(upstream.getHost());
                        if (authorization != null) {
                            connect.append("Proxy-Authorization: ").append(authorization).append("\r\n");
                        }

                        connect.append("\r\n");

                        final ByteBuffer buffer = ByteBuffer.wrap(connect.toString().getBytes(StandardCharsets.US_ASCII));
                        while (buffer.hasRemaining()) {
                            channel.write(buffer);
                        }
                        responseBuf = new StringBuilder();
                    } else if (key.isReadable() && channel.isConnected()) {
                        if (responseBuf == null) {
                            responseBuf = new StringBuilder();
                        }
                        final ByteBuffer buffer = ByteBuffer.allocate(1024);
                        int n;
                        while ((n = channel.read(buffer)) > 0) {
                            buffer.flip();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            responseBuf.append(new String(bytes, StandardCharsets.US_ASCII));
                            totalRead += n;
                            buffer.clear();
                            if (totalRead > MAX_RESPONSE_BYTES) {
                                throw new IOException("Upstream proxy response exceeded " + MAX_RESPONSE_BYTES + " bytes");
                            }
                        }
                        if (n < 0) {
                            throw new IOException("Upstream proxy " + upstream.getHost() + ":" + upstream.getPort() + " closed connection before sending CONNECT response");
                        }
                        // Wait until we've seen the end of headers
                        if (responseBuf.indexOf("\r\n\r\n") < 0) {
                            continue;
                        }

                        String statusLine = responseBuf.substring(0, responseBuf.indexOf("\r\n"));
                        if (!isSuccessfulConnect(statusLine)) {
                            throw new IOException(String.format(
                                "Upstream proxy (%s:%d) rejected CONNECT to %s:%d. Status: %s",
                                upstream.getHost(), upstream.getPort(), host, port, statusLine));
                        }

                        if (debugMode) {
                            LOG.info("Successfully established CONNECT tunnel through upstream proxy {}:{} to {}:{}",
                                    upstream.getHost(), upstream.getPort(), host, port);
                        }

                        selector.close();
                        promise.succeeded(channel);
                        return;
                    }
                }
            }
        } catch (Throwable x) {
            // Throwable, not IOException: an unresolvable --proxy host makes channel.connect
            // throw UnresolvedAddressException, a RuntimeException. That escaped the method,
            // leaking the SocketChannel on every CONNECT and leaving the dial promise (and so
            // the client's request) uncompleted.
            LOG.error("Failed to establish CONNECT tunnel through upstream proxy {}:{} to {}:{}: {}",
                    upstream.getHost(), upstream.getPort(), host, port, x.getMessage());
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException t) {
                    LOG.error("Error closing channel after failure: {}", t.getMessage(), t);
                }
            }
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException t) {
                    LOG.error("Error closing selector after failure: {}", t.getMessage(), t);
                }
            }
            promise.failed(x);
        }
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
