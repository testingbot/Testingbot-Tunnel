package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.Statistics;
import com.testingbot.tunnel.TunnelMetrics;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.proxy.ProxyHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * The tunnel's local forward proxy: browsers and tests point at it, and it relays plain HTTP
 * out to the internet (HTTPS arrives as CONNECT and is handled by CustomConnectHandler).
 *
 * <p>On top of Jetty's proxying this adds the tunnel's own concerns: a fast-fail blacklist,
 * injected headers, upstream proxy and per-host basic auth, and the Prometheus metrics.
 */
public class TunnelProxyHandler extends ProxyHandler.Forward {

    private static final Logger LOG = Logger.getLogger(TunnelProxyHandler.class.getName());

    /** Jetty's default is 64; a proxy serving a whole browser session fans out wider. */
    private static final int MAX_CONNECTIONS_PER_DESTINATION = 256;
    /** Relay buffers: bigger reads mean fewer syscalls per megabyte proxied. */
    private static final int CLIENT_BUFFER_SIZE = 32 * 1024;

    // Keep label cardinality bounded by collapsing non-standard verbs.
    private static final Set<String> KNOWN_METHODS = new HashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "TRACE", "CONNECT"));

    private List<Pattern> blackList = Collections.emptyList();
    private Map<String, String> extraHeaders = Collections.emptyMap();
    private String proxyAuthHeaderValue;
    private String upstreamProxy;
    private String upstreamProxyAuth;
    private String[] basicAuth;
    private boolean debugMode;
    private long idleTimeoutMs = 120_000L;

    public void setBlackList(String[] patterns) {
        this.blackList = compilePatterns(patterns);
    }

    public void setExtraHeaders(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders == null ? Collections.emptyMap() : extraHeaders;
    }

    public void setUpstreamProxy(String hostPort, String userPassword) {
        this.upstreamProxy = hostPort;
        this.upstreamProxyAuth = userPassword;
        if (userPassword != null && !userPassword.isEmpty()) {
            this.proxyAuthHeaderValue = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(userPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public void setBasicAuth(String[] basicAuth) {
        this.basicAuth = basicAuth;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    static List<Pattern> compilePatterns(String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            return Collections.emptyList();
        }
        List<Pattern> compiled = new ArrayList<>(patterns.length);
        for (String entry : patterns) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            String trimmed = entry.trim();
            try {
                compiled.add(Pattern.compile(trimmed));
            } catch (PatternSyntaxException ex) {
                LOG.log(Level.WARNING, "Invalid fast-fail pattern ''{0}'' ignored: {1}",
                        new Object[]{trimmed, ex.getDescription()});
            }
        }
        return Collections.unmodifiableList(compiled);
    }

    static boolean hostMatchesAny(String host, List<Pattern> patterns) {
        if (host == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(host).find()) {
                return true;
            }
        }
        return false;
    }

    static String methodLabel(String method) {
        if (method == null) {
            return "OTHER";
        }
        String upper = method.toUpperCase(Locale.ROOT);
        return KNOWN_METHODS.contains(upper) ? upper : "OTHER";
    }

    @Override
    protected void configureHttpClient(HttpClient client) {
        super.configureHttpClient(client);
        client.setIdleTimeout(idleTimeoutMs);
        // A forward proxy fans out to many origins at once and relays bodies rather than
        // parsing them, so Jetty's request/response defaults (sized for an application
        // client talking to one server) are low on both counts.
        client.setMaxConnectionsPerDestination(MAX_CONNECTIONS_PER_DESTINATION);
        client.setRequestBufferSize(CLIENT_BUFFER_SIZE);
        client.setResponseBufferSize(CLIENT_BUFFER_SIZE);

        if (upstreamProxy != null && !upstreamProxy.isEmpty()) {
            String[] split = upstreamProxy.split(":", 2);
            if (split.length < 2) {
                LOG.log(Level.WARNING, "Invalid proxy format, expected host:port");
            } else {
                ProxyConfiguration proxyConfig = client.getProxyConfiguration();
                // Jetty 12.1 returns an immutable list from getProxies(); addProxy() is the mutator.
                proxyConfig.addProxy(new HttpProxy(split[0], Integer.parseInt(split[1])));
                if (upstreamProxyAuth != null && !upstreamProxyAuth.isEmpty()) {
                    LOG.log(Level.INFO, "Proxy authentication configured");
                }
            }
        }

        if (basicAuth != null) {
            AuthenticationStore auth = client.getAuthenticationStore();
            for (String entry : basicAuth) {
                String[] credentials = entry.split(":", 4);
                if (credentials.length < 4) {
                    LOG.log(Level.WARNING, "Invalid basic auth format, expected host:port:user:password");
                    continue;
                }
                LOG.log(Level.INFO, "Adding Basic Auth for {0}:{1}",
                        new Object[]{credentials[0], credentials[1]});
                try {
                    auth.addAuthentication(new BasicAuthentication(
                            new URI("http://" + credentials[0] + ":" + credentials[1]),
                            Authentication.ANY_REALM, credentials[2], credentials[3]));
                } catch (URISyntaxException ex) {
                    LOG.log(Level.SEVERE, "Invalid URI for basic auth", ex);
                }
            }
        }
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String method = methodLabel(request.getMethod());

        if (!blackList.isEmpty()) {
            String host = Request.getServerName(request);
            if (hostMatchesAny(host, blackList)) {
                LOG.log(Level.INFO, "Fast-fail: rejecting {0} (matched blacklist)", host);
                TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, "403").inc();
                TunnelMetrics.ERRORS_TOTAL.labels("blacklisted").inc();
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403,
                        "Blocked by fast-fail policy");
                return true;
            }
        }

        long requestLength = request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
        if (requestLength >= 0) {
            TunnelMetrics.HTTP_REQUEST_SIZE_BYTES.labels(method).observe(requestLength);
        }

        if (debugMode) {
            StringBuilder sb = new StringBuilder();
            for (HttpField field : request.getHeaders()) {
                sb.append(field.getName()).append(": ")
                        .append(SensitiveHeaders.redactValue(field.getName(), field.getValue()))
                        .append(System.lineSeparator());
            }
            LOG.log(Level.INFO, sb.toString());
        }

        TunnelMetrics.HTTP_REQUESTS_IN_FLIGHT.labels(method).inc();
        long startTime = System.currentTimeMillis();

        // Proxying is asynchronous: the exchange is only finished when the callback
        // completes, so record outcome metrics there rather than when handle() returns.
        Callback observed = Callback.from(callback, () -> {
            long elapsed = System.currentTimeMillis() - startTime;
            Statistics.addRequest();
            TunnelMetrics.HTTP_REQUESTS_IN_FLIGHT.labels(method).dec();
            TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, Integer.toString(response.getStatus())).inc();
            TunnelMetrics.HTTP_REQUEST_DURATION_SECONDS.labels(method).observe(elapsed / 1000.0);
            TunnelMetrics.HTTP_RESPONSE_SIZE_BYTES.labels(method)
                    .observe(responseBytes(request).doubleValue());
            LOG.log(Level.INFO, "[{0}] {1} ({2}) - {3} ms",
                    new Object[]{request.getMethod(), request.getHttpURI(), response.getStatus(), elapsed});
        });

        return super.handle(request, response, observed);
    }

    /** Per-request response byte counter, kept as a request attribute. */
    private static final String ATTR_RESPONSE_BYTES = "tb.responseBytes";

    private static AtomicLong responseBytes(Request request) {
        Object existing = request.getAttribute(ATTR_RESPONSE_BYTES);
        if (existing instanceof AtomicLong) {
            return (AtomicLong) existing;
        }
        AtomicLong counter = new AtomicLong();
        request.setAttribute(ATTR_RESPONSE_BYTES, counter);
        return counter;
    }

    @Override
    protected void addProxyHeaders(Request clientToProxyRequest,
                                   org.eclipse.jetty.client.Request proxyToServerRequest) {
        super.addProxyHeaders(clientToProxyRequest, proxyToServerRequest);

        proxyToServerRequest.headers(fields -> {
            if (proxyAuthHeaderValue != null) {
                fields.put(HttpHeader.PROXY_AUTHORIZATION, proxyAuthHeaderValue);
            }
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                fields.put(entry.getKey(), entry.getValue());
            }
        });
    }

    @Override
    protected org.eclipse.jetty.client.Response.CompleteListener newServerToProxyResponseListener(
            Request clientToProxyRequest,
            org.eclipse.jetty.client.Request proxyToServerRequest,
            Response proxyToClientResponse,
            Callback proxyToClientCallback) {
        return new CountingResponseListener(clientToProxyRequest, proxyToServerRequest,
                proxyToClientResponse, proxyToClientCallback);
    }

    /**
     * Counts response bytes per request so HTTP_RESPONSE_SIZE_BYTES stays accurate for
     * chunked responses, where Content-Length is absent.
     */
    private class CountingResponseListener extends ProxyHandler.ProxyResponseListener {
        private final Request clientToProxyRequest;

        CountingResponseListener(Request clientToProxyRequest,
                                 org.eclipse.jetty.client.Request proxyToServerRequest,
                                 Response proxyToClientResponse,
                                 Callback proxyToClientCallback) {
            super(clientToProxyRequest, proxyToServerRequest, proxyToClientResponse, proxyToClientCallback);
            this.clientToProxyRequest = clientToProxyRequest;
        }

        @Override
        public void onContent(org.eclipse.jetty.client.Response serverToProxyResponse,
                              Content.Chunk serverToProxyChunk,
                              Runnable serverToProxyDemander) {
            responseBytes(clientToProxyRequest).addAndGet(serverToProxyChunk.remaining());
            super.onContent(serverToProxyResponse, serverToProxyChunk, serverToProxyDemander);
        }
    }

    @Override
    protected void onServerToProxyResponseFailure(Request clientToProxyRequest,
                                                  org.eclipse.jetty.client.Request proxyToServerRequest,
                                                  org.eclipse.jetty.client.Response serverToProxyResponse,
                                                  Response proxyToClientResponse,
                                                  Callback proxyToClientCallback,
                                                  Throwable failure) {
        TunnelMetrics.ERRORS_TOTAL.labels("client_request_failure").inc();
        String uri = String.valueOf(clientToProxyRequest.getHttpURI());
        if (!uri.contains("squid-internal")) {
            LOG.log(Level.WARNING, "{0} for request {1} - {2}",
                    new Object[]{failure.getMessage(), clientToProxyRequest.getMethod(), uri});
            LOG.log(Level.SEVERE,
                    "Local proxy received a connection failure from upstream. Make sure the website"
                    + " you want to test is accessible from this machine.");
        }
        super.onServerToProxyResponseFailure(clientToProxyRequest, proxyToServerRequest,
                serverToProxyResponse, proxyToClientResponse, proxyToClientCallback, failure);
    }
}
