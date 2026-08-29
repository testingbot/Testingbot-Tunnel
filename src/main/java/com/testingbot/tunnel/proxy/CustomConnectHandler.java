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

    private final String proxyHost;
    private final int proxyPort;
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
        // requestURI for CONNECT is "host:port"; strip port if present
        int colon = hostHeader.indexOf(':');
        String host = colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
        host = host.toLowerCase(Locale.ROOT);
        for (Pattern p : patterns) {
            if (p.matcher(host).find()) {
                return true;
            }
        }
        return false;
    }

    public CustomConnectHandler(final App app) {
        final String proxy = app.getProxy();
        if (proxy != null) {
            final int colon = proxy.indexOf(':');
            if (colon != -1) {
                proxyHost = proxy.substring(0, colon);
                proxyPort = Integer.parseInt(proxy.substring(colon + 1));
            } else {
                proxyHost = proxy;
                proxyPort = 80;
            }
        } else {
            proxyHost = null;
            proxyPort = -1;
        }

        if (app.getProxyAuth() != null) {
            proxyAuth = Base64.getEncoder().encodeToString(app.getProxyAuth().getBytes(StandardCharsets.UTF_8));
        }
    }

    public void setDebugMode(boolean mode) {
        debugMode = mode;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        Statistics.addRequest();

        boolean isConnect = HttpMethod.CONNECT.is(request.getMethod());
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
            if (status >= 400) {
                TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels("status_" + status).inc();
            }
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
            super.connectToServer(request, host, port, promise);
        } else {
            connectToProxy(request, host, port, promise);
        }
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
        } catch (IOException x) {
            TunnelMetrics.HTTPS_CONNECT_ERRORS_TOTAL.labels("upstream_connect_failed").inc();
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
