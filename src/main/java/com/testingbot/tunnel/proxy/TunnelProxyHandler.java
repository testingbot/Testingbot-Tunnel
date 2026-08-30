package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.Statistics;
import com.testingbot.tunnel.TunnelMetrics;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import org.eclipse.jetty.client.Socks5;
import org.eclipse.jetty.client.Socks5Proxy;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
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

    private FastFailPolicy fastFail = FastFailPolicy.none();
    private Map<String, String> extraHeaders = Collections.emptyMap();
    private HeaderRules requestHeaderRules = HeaderRules.none();
    private HeaderRules responseHeaderRules = HeaderRules.none();
    private String proxyAuthHeaderValue;
    private String upstreamProxy;
    private String upstreamProxyAuth;
    private String[] basicAuth;
    private boolean debugMode;
    private CustomDnsResolver dnsResolver;
    private ConnectToMap connectTo = ConnectToMap.none();
    private LocalhostPolicy localhostPolicy = LocalhostPolicy.ALLOW;
    private long idleTimeoutMs = 120_000L;

    public void setBlackList(String[] patterns) {
        this.fastFail = FastFailPolicy.compile(patterns);
    }

    public void setRequestHeaderRules(HeaderRules rules) {
        this.requestHeaderRules = rules == null ? HeaderRules.none() : rules;
    }

    public void setResponseHeaderRules(HeaderRules rules) {
        this.responseHeaderRules = rules == null ? HeaderRules.none() : rules;
    }

    public void setExtraHeaders(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders == null ? Collections.emptyMap() : extraHeaders;
    }

    public void setUpstreamProxy(String hostPort, String userPassword) {
        this.upstreamProxy = hostPort;
        this.upstreamProxyAuth = userPassword;
        // Proxy-Authorization is an HTTP-proxy mechanism. SOCKS authenticates during its own
        // handshake, so sending the header there would leak the credentials to the origin.
        // Only when an HTTP upstream proxy will actually be configured. spec is null when
        // --proxy is absent or unparseable, and in that case requests go straight to origins:
        // stamping Proxy-Authorization on those would hand the customer's proxy credentials to
        // every website their tests visit.
        ProxySpec spec = ProxySpec.parse(hostPort);
        boolean httpProxy = spec != null && !spec.isSocks5();
        if (httpProxy && userPassword != null && !userPassword.isEmpty()) {
            this.proxyAuthHeaderValue = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(userPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Splits "user:password"; the password may itself contain colons. */
    static String[] splitCredentials(String userPassword) {
        if (userPassword == null || userPassword.isEmpty()) {
            return null;
        }
        int colon = userPassword.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return new String[]{userPassword.substring(0, colon), userPassword.substring(colon + 1)};
    }

    public void setBasicAuth(String[] basicAuth) {
        this.basicAuth = basicAuth;
    }

    public void setDnsResolver(CustomDnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    public void setLocalhostPolicy(LocalhostPolicy localhostPolicy) {
        this.localhostPolicy = localhostPolicy == null ? LocalhostPolicy.ALLOW : localhostPolicy;
    }

    public void setConnectTo(ConnectToMap connectTo) {
        this.connectTo = connectTo == null ? ConnectToMap.none() : connectTo;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
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

        if (dnsResolver != null || !connectTo.isEmpty()) {
            // Resolving is where --connect-to belongs: it changes where we dial without
            // touching the request URI, so the Host header and TLS SNI still carry the name
            // the caller asked for. Rewriting the request instead would defeat the purpose.
            //
            // Jetty resolves asynchronously; the lookup is blocking, so hand it to the client's
            // executor rather than running it on the caller's thread.
            client.setSocketAddressResolver(new org.eclipse.jetty.util.SocketAddressResolver() {
                // Built on first use, not here: the client's executor and scheduler are only
                // available once it has started, and getSocketAddressResolver() is still null
                // at configure time.
                private volatile org.eclipse.jetty.util.SocketAddressResolver platform;

                @Override
                public void resolve(String host, int port, java.util.Map<String, Object> context,
                                    org.eclipse.jetty.util.Promise<List<InetSocketAddress>> promise) {
                    ConnectToMap.Target target = connectTo.remap(host, port);
                    if (dnsResolver == null) {
                        platformResolver().resolve(target.host(), target.port(), context, promise);
                        return;
                    }
                    client.getExecutor().execute(() -> {
                        try {
                            List<InetSocketAddress> resolved = new ArrayList<>();
                            for (InetAddress address : dnsResolver.resolve(target.host())) {
                                resolved.add(new InetSocketAddress(address, target.port()));
                            }
                            promise.succeeded(resolved);
                        } catch (Throwable x) {
                            promise.failed(x);
                        }
                    });
                }

                private org.eclipse.jetty.util.SocketAddressResolver platformResolver() {
                    org.eclipse.jetty.util.SocketAddressResolver resolver = platform;
                    if (resolver == null) {
                        resolver = new org.eclipse.jetty.util.SocketAddressResolver.Async(
                                client.getExecutor(), client.getScheduler(),
                                client.getAddressResolutionTimeout());
                        platform = resolver;
                    }
                    return resolver;
                }
            });
        }
        // A forward proxy fans out to many origins at once and relays bodies rather than
        // parsing them, so Jetty's request/response defaults (sized for an application
        // client talking to one server) are low on both counts.
        client.setMaxConnectionsPerDestination(MAX_CONNECTIONS_PER_DESTINATION);
        client.setRequestBufferSize(CLIENT_BUFFER_SIZE);
        client.setResponseBufferSize(CLIENT_BUFFER_SIZE);

        ProxySpec spec = ProxySpec.parse(upstreamProxy);
        if (upstreamProxy != null && !upstreamProxy.isEmpty() && spec == null) {
            LOG.log(Level.WARNING,
                    "Invalid proxy format ''{0}''; expected host:port, http://host:port or socks5://host:port",
                    upstreamProxy);
        } else if (spec != null) {
            // Jetty 12.1 returns an immutable list from getProxies(); addProxy() is the mutator.
            ProxyConfiguration proxyConfig = client.getProxyConfiguration();
            if (spec.isSocks5()) {
                Socks5Proxy socks = new Socks5Proxy(spec.getHost(), spec.getPort());
                String[] credentials = splitCredentials(upstreamProxyAuth);
                if (credentials != null) {
                    socks.putAuthenticationFactory(
                            new Socks5.UsernamePasswordAuthenticationFactory(credentials[0], credentials[1]));
                }
                proxyConfig.addProxy(socks);
            } else {
                proxyConfig.addProxy(new HttpProxy(spec.getHost(), spec.getPort()));
            }
            if (upstreamProxyAuth != null && !upstreamProxyAuth.isEmpty()) {
                LOG.log(Level.INFO, "Proxy authentication configured");
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

        String targetHost = Request.getServerName(request);
        if (localhostPolicy.blocks(targetHost)) {
            LOG.log(Level.INFO, "Localhost policy: rejecting {0}", targetHost);
            TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, "403").inc();
            TunnelMetrics.ERRORS_TOTAL.labels("denied_localhost").inc();
            Statistics.addRequest();
            ProxyErrors.write(request, response, callback, ProxyErrors.Reason.DENIED_LOCALHOST);
            return true;
        }

        if (!fastFail.isEmpty()) {
            String host = targetHost;
            if (fastFail.blocks(host)) {
                LOG.log(Level.INFO, "Fast-fail: rejecting {0} (matched blacklist)", host);
                TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, "403").inc();
                TunnelMetrics.ERRORS_TOTAL.labels("blacklisted").inc();
                // Counted here because the request returns before `observed` below is built.
                // Leaving it out made the JSON numberOfRequests disagree with
                // HTTP_REQUESTS_TOTAL by exactly the fast-failed requests.
                Statistics.addRequest();
                ProxyErrors.write(request, response, callback, ProxyErrors.Reason.DENIED_BY_FAST_FAIL);
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

        // Seeded before the exchange: ProxyHandler puts the first occurrence of each response
        // header, so a value written afterwards would be overwritten by the origin's. Its copy
        // is dropped in filterServerToProxyResponseField, leaving ours.
        responseHeaderRules.applySets(response.getHeaders());

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

    /**
     * Builds the proxy-to-server request without routing the target through {@link java.net.URI}.
     *
     * <p>{@link ProxyHandler}'s implementation calls {@code HttpURI.toURI()}, and
     * {@code java.net.URI} enforces RFC 2396, which forbids characters Jetty's own lenient
     * {@code HttpURI} parser has already accepted -- braces, pipes and a bare {@code %} among
     * them. The result was an IllegalArgumentException surfacing as a 500 for query strings
     * that arrive perfectly often in practice ({@code ?filter={"a":1\}}, {@code ?next=a|b},
     * {@code ?q=100%}).
     *
     * <p>Handing the raw path and query to {@code Request.path(String)} avoids the conversion:
     * it tries {@code java.net.URI} itself but falls back to the string verbatim when parsing
     * fails, which is the leniency a forward proxy needs. The invariant we want is simply that
     * anything the server accepted, the proxy can forward.
     */
    @Override
    protected org.eclipse.jetty.client.Request newProxyToServerRequest(Request clientToProxyRequest,
                                                                       HttpURI newHttpURI) {
        int port = newHttpURI.getPort();
        if (port <= 0) {
            port = HttpScheme.HTTPS.is(newHttpURI.getScheme()) ? 443 : 80;
        }
        String pathQuery = newHttpURI.getPathQuery();
        if (pathQuery == null || pathQuery.isEmpty()) {
            pathQuery = "/";
        }
        org.eclipse.jetty.client.Request proxyRequest =
                getHttpClient().newRequest(newHttpURI.getHost(), port)
                        .scheme(newHttpURI.getScheme())
                        .path(pathQuery)
                        // Jetty 11's ProxyServlet applied a total exchange timeout from its
                        // "timeout" init parameter; Jetty 12's ProxyHandler never calls
                        // Request.timeout(), so a response that trickles forever was no longer
                        // bounded by anything.
                        .timeout(idleTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .method(clientToProxyRequest.getMethod());

        // RFC 9112 3.2.2: requests to a forward proxy must use absolute-form. jetty-client
        // normally rewrites the target itself, but only when getURI() is non-null -- and path()
        // leaves getURI() null for exactly the query strings the lenient fallback above exists
        // to pass through ("?a={json}", "?next=a|b", "?tpl={{name}}"), because java.net.URI
        // rejects them. Those requests would reach the upstream proxy in origin-form, which
        // Squid answers with 400. Absolutize them here, the same way jetty-client would.
        if (usesHttpUpstreamProxy() && proxyRequest.getURI() == null) {
            proxyRequest.path(absoluteForm(newHttpURI.getScheme(), newHttpURI.getHost(), port, pathQuery));
        }
        return proxyRequest;
    }

    /** True when traffic leaves through an HTTP forward proxy. SOCKS5 is transparent at TCP level. */
    private boolean usesHttpUpstreamProxy() {
        ProxySpec spec = ProxySpec.parse(upstreamProxy);
        return spec != null && !spec.isSocks5();
    }

    static String absoluteForm(String scheme, String host, int port, String pathQuery) {
        boolean defaultPort = HttpScheme.HTTPS.is(scheme) ? port == 443 : port == 80;
        StringBuilder sb = new StringBuilder(scheme).append("://");
        // Bracket IPv6 literals, which the authority form requires -- unless HttpURI already
        // did. Jetty's HttpURI.getHost() keeps the brackets, so bracketing unconditionally
        // produced "[[::1]]" and an unparseable request target.
        boolean ipv6 = host != null && host.indexOf(':') >= 0 && !host.startsWith("[");
        sb.append(ipv6 ? "[" + host + "]" : host);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        return sb.append(pathQuery).toString();
    }

    @Override
    protected void addProxyHeaders(Request clientToProxyRequest,
                                   org.eclipse.jetty.client.Request proxyToServerRequest) {
        super.addProxyHeaders(clientToProxyRequest, proxyToServerRequest);

        proxyToServerRequest.headers(fields -> {
            if (proxyAuthHeaderValue != null) {
                fields.put(HttpHeader.PROXY_AUTHORIZATION, proxyAuthHeaderValue);
            }
            // Jetty 11's AbstractProxyServlet added these; Jetty 12's ProxyHandler sends only
            // Via and Forwarded, so targets that key off X-Forwarded-* (very common for staging
            // environments behind a load balancer) silently stopped seeing them.
            String remote = Request.getRemoteAddr(clientToProxyRequest);
            if (remote != null) {
                fields.add("X-Forwarded-For", remote);
            }
            HttpURI clientUri = clientToProxyRequest.getHttpURI();
            if (clientUri.getScheme() != null) {
                fields.add("X-Forwarded-Proto", clientUri.getScheme());
            }
            String hostHeader = clientToProxyRequest.getHeaders().get(HttpHeader.HOST);
            if (hostHeader != null) {
                fields.add("X-Forwarded-Host", hostHeader);
            }
            // The proxy's own address. Request.getServerName() returns the *target* host
            // parsed from the request line, which would make this header a copy of
            // X-Forwarded-Host rather than the hop identity Jetty 11 sent.
            String local = Request.getLocalAddr(clientToProxyRequest);
            if (local != null) {
                fields.add("X-Forwarded-Server", local);
            }
            // add(), not put(): the old servlet appended, so a header the client already sent
            // AND --extra-headers configured reached the origin with both values.
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                fields.add(entry.getKey(), entry.getValue());
            }
            // Last, so --header can remove or override anything above -- including a header
            // --extra-headers added, or one of the X-Forwarded-* we generate.
            requestHeaderRules.applyTo(fields);
        });
    }

    /**
     * Response headers, in the one hook Jetty offers per field.
     *
     * <p>Removals and overrides drop the origin's field here; the replacement values are seeded
     * onto the response in {@link #handle} before the copy runs, because ProxyHandler has no
     * after-the-copy hook and {@code put}s the first occurrence of each name.
     */
    @Override
    protected HttpField filterServerToProxyResponseField(HttpField field) {
        if (responseHeaderRules.drops(field.getName())) {
            return null;
        }
        return super.filterServerToProxyResponseField(field);
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
            int chunk = serverToProxyChunk.remaining();
            responseBytes(clientToProxyRequest).addAndGet(chunk);
            // Counts plain-HTTP response bytes only, which is what the metric name and the
            // "HTTP Response Throughput" dashboard panel mean. Tunnelled traffic is counted
            // at the connector instead (testingbot_connection_bytes_*), because CONNECT and
            // WebSocket bodies are opaque here.
            TunnelMetrics.PROXY_BYTES_TRANSFERRED_TOTAL.inc(chunk);
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
        String uri = String.valueOf(clientToProxyRequest.getHttpURI());
        ProxyErrors.Reason reason = ProxyErrors.classify(failure);

        TunnelMetrics.ERRORS_TOTAL.labels(reason == ProxyErrors.Reason.MALFORMED_REQUEST_URI
                ? "malformed_request_uri" : "client_request_failure").inc();

        // squid-internal requests are the caching layer probing itself; a failure there is
        // noise, not something the customer can act on.
        if (!uri.contains("squid-internal")) {
            LOG.log(Level.WARNING, "{0}: {1} {2} ({3})", new Object[]{
                    reason.reason(), clientToProxyRequest.getMethod(), uri, failure.getMessage()});
        }

        ProxyErrors.write(clientToProxyRequest, proxyToClientResponse, proxyToClientCallback, reason);
    }
}
