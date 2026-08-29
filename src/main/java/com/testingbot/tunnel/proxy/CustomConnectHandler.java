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
    private String proxyAuth = null;
    private List<Pattern> blackList = Collections.emptyList();

    public void setBlackList(String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            this.blackList = Collections.emptyList();
            return;
        }
        List<Pattern> compiled = new ArrayList<>(patterns.length);
        for (String entry : patterns) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(trimmed));
            } catch (PatternSyntaxException ex) {
                Logger.getLogger(CustomConnectHandler.class.getName())
                    .log(Level.WARNING, "Invalid fast-fail pattern ''{0}'' ignored: {1}",
                        new Object[]{trimmed, ex.getDescription()});
            }
        }
        this.blackList = Collections.unmodifiableList(compiled);
    }

    static boolean hostBlocked(String hostHeader, List<Pattern> patterns) {
        if (hostHeader == null || patterns.isEmpty()) {
            return false;
        }
        // May arrive as "host", "host:port" or a bracketed IPv6 literal. Truncating at the
        // first colon turned "[::1]" into "[", so no pattern could ever match an IPv6 target.
        String host = hostHeader;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(1, close) : host;
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0 && host.indexOf(':', colon + 1) < 0) {
                host = host.substring(0, colon);   // host:port, not a bare IPv6 literal
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        for (Pattern p : patterns) {
            if (p.matcher(host).find()) {
                return true;
            }
        }
        return false;
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
        // inside its own handshake.
        if (proxyUserPassword != null && (proxySpec == null || !proxySpec.isSocks5())) {
            proxyAuth = Base64.getEncoder().encodeToString(proxyUserPassword.getBytes(StandardCharsets.UTF_8));
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
        if (dnsResolver != null) {
            try {
                return new java.net.InetSocketAddress(dnsResolver.resolve(host)[0], port);
            } catch (java.net.UnknownHostException ex) {
                LOG.warn("Custom DNS could not resolve {}: {}", host, ex.getMessage());
            }
        }
        return super.newConnectAddress(host, port);
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
        }
        ProxyErrors.Reason reason = ProxyErrors.classify(failure);
        TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels(reason.reason().replace('-', '_')).inc();
        LOG.warn("CONNECT failed ({}): {}", reason.reason(),
                failure == null ? "unknown" : failure.getMessage());
        ProxyErrors.write(request, response, callback, reason);
    }

    @Override
    protected void onConnectSuccess(ConnectContext connectContext, UpstreamConnection upstreamConnection) {
        if (proxyHost == null) {
            TunnelMetrics.DIAL_TOTAL.labels("connect", "success").inc();
        }
        super.onConnectSuccess(connectContext, upstreamConnection);
    }

    @Override
    public boolean validateDestination(String host, int port) {
        if (hostBlocked(host, blackList)) {
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
        if (proxyHost == null) {
            // Not timed here: Jetty's default connectToServer succeeds the promise as soon as
            // the non-blocking connect is *initiated*, so wrapping it recorded every dial as an
            // instant success and never saw a refusal. The direct path is measured in
            // onConnectSuccess/onConnectFailure instead, where the outcome is actually known.
            super.connectToServer(request, host, port, promise);
            return;
        }
        Promise<SocketChannel> timed = TunnelMetrics.timedDial("connect", promise);
        if (proxySpec.isSocks5()) {
            connectViaSocks5(host, port, timed);
        } else {
            connectToProxy(request, host, port, timed);
        }
    }

    /**
     * Opens the tunnel through a SOCKS5 upstream proxy. The handshake is short and blocking,
     * so it runs on the executor rather than being woven into the selector loop the HTTP
     * CONNECT path uses; the channel is switched back to non-blocking before Jetty gets it.
     */
    private void connectViaSocks5(String host, int port, Promise<SocketChannel> promise) {
        getExecutor().execute(() -> {
            SocketChannel channel = null;
            try {
                channel = SocketChannel.open();
                channel.socket().setTcpNoDelay(true);
                channel.socket().connect(newConnectAddress(proxyHost, proxyPort), (int) getConnectTimeout());
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
                            proxyHost, proxyPort, host, port);
                }
                promise.succeeded(channel);
            } catch (Throwable x) {
                LOG.error("Failed to establish SOCKS5 tunnel through {}:{} to {}:{}: {}",
                        proxyHost, proxyPort, host, port, x.getMessage());
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

    private void connectToProxy(Request request, String host, int port, Promise<SocketChannel> promise) {
        SocketChannel channel = null;
        Selector selector = null;
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            channel.connect(newConnectAddress(proxyHost, proxyPort));

            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_CONNECT);

            final long deadline = System.currentTimeMillis() + SELECT_TIMEOUT_MS;
            StringBuilder responseBuf = null;
            int totalRead = 0;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IOException("Timed out waiting for upstream proxy " + proxyHost + ":" + proxyPort);
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
                            throw new IOException("finishConnect() returned false for " + proxyHost + ":" + proxyPort);
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

                        if (proxyAuth != null) {
                            connect.append("Proxy-Authorization: Basic ").append(proxyAuth).append("\r\n");
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
                            throw new IOException("Upstream proxy " + proxyHost + ":" + proxyPort + " closed connection before sending CONNECT response");
                        }
                        // Wait until we've seen the end of headers
                        if (responseBuf.indexOf("\r\n\r\n") < 0) {
                            continue;
                        }

                        String statusLine = responseBuf.substring(0, responseBuf.indexOf("\r\n"));
                        if (!isSuccessfulConnect(statusLine)) {
                            throw new IOException(String.format(
                                "Upstream proxy (%s:%d) rejected CONNECT to %s:%d. Status: %s",
                                proxyHost, proxyPort, host, port, statusLine));
                        }

                        if (debugMode) {
                            LOG.info("Successfully established CONNECT tunnel through upstream proxy {}:{} to {}:{}",
                                    proxyHost, proxyPort, host, port);
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
                    proxyHost, proxyPort, host, port, x.getMessage());
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
