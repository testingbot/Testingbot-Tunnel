package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.Statistics;
import com.testingbot.tunnel.TunnelMetrics;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private AllowedHosts allowedHosts = AllowedHosts.unrestricted();
    private Map<String, String> extraHeaders = Collections.emptyMap();
    private ProxyAuthenticator proxyAuthenticator = ProxyAuthenticator.none();
    private String negotiateServiceName;
    private java.nio.file.Path krb5KeyTab;
    private String krb5Principal;
    private NegotiateHosts negotiateHosts = NegotiateHosts.none();
    /** Built once the host list is known; derives HTTP/<host> per request. */
    private SpnegoClient originSpnego;
    private String requestIdHeader = "X-Request-Id";
    private HeaderRules requestHeaderRules = HeaderRules.none();
    private HeaderRules responseHeaderRules = HeaderRules.none();
    private String proxyAuthHeaderValue;
    /** Host of the upstream HTTP proxy, or null when there is not one. */
    private String upstreamHttpProxyHost;
    private String upstreamProxy;
    private String upstreamProxyAuth;
    private String[] basicAuth;
    private Map<String, String> siteCredentials = Map.of();
    private boolean debugMode;
    private CustomDnsResolver dnsResolver;
    private ConnectToMap connectTo = ConnectToMap.none();
    private LocalhostPolicy localhostPolicy = LocalhostPolicy.ALLOW;
    private com.testingbot.tunnel.pac.PacPolicy pacPolicy;
    private long idleTimeoutMs = 120_000L;
    private long connectTimeoutMs = -1;

    public void setAllowedHosts(AllowedHosts allowedHosts) {
        this.allowedHosts = allowedHosts == null ? AllowedHosts.unrestricted() : allowedHosts;
    }

    public void setBlackList(String[] patterns) {
        this.fastFail = FastFailPolicy.compile(patterns);
    }

    public void setRequestIdHeader(String requestIdHeader) {
        this.requestIdHeader = requestIdHeader == null || requestIdHeader.isEmpty()
                ? "X-Request-Id" : requestIdHeader;
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
        this.upstreamHttpProxyHost = httpProxy ? spec.getHost() : null;
        if (httpProxy && userPassword != null && !userPassword.isEmpty()) {
            this.proxyAuthHeaderValue = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(userPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * The {@code Proxy-Authorization} value for the upstream proxy, or null when there is
     * nothing to send.
     *
     * <p>Negotiate is asked for a fresh token per request: SPNEGO tokens carry a timestamp and
     * sequence number, so replaying one is exactly what a proxy is meant to reject.
     *
     * <p>Sent pre-emptively for the same reason {@code --auth} is. Jetty 12's
     * ProxyHandler.newHttpClient() ends with {@code protocolHandlers.clear()}, which removes the
     * handler that answers a 407 -- so the SPNEGOAuthentication configured on the client could
     * never fire and every request through a Negotiate proxy failed. The CONNECT and SSH paths
     * were already pre-emptive; this makes the plain-HTTP path agree with them.
     */
    private String upstreamAuthorization() {
        if (upstreamHttpProxyHost == null) {
            return null;
        }
        if (proxyAuthenticator.isNegotiate()) {
            return proxyAuthenticator.authorizationValue(upstreamHttpProxyHost);
        }
        return proxyAuthHeaderValue;
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
        this.siteCredentials = parseSiteCredentials(basicAuth);
    }

    /**
     * {@code host:port} to a ready-made {@code Basic ...} value, for {@code --auth}.
     *
     * <p>These used to be handed to jetty-client's AuthenticationStore alone, which answers a
     * 401 by retrying with credentials. Jetty 12's ProxyHandler ends newHttpClient() with
     * {@code protocolHandlers.clear()}, under a comment saying this configuration "should not be
     * customized" -- so the handler that reacts to the challenge is removed after we configure
     * the client, and the stored credential could never be sent. The 401 went straight back to
     * the caller. Sending it pre-emptively is also better behaviour: it is what the option
     * promises, and it costs one round trip fewer.
     *
     * <p>The store is still populated, so a target that challenges on a path we did not
     * anticipate is unaffected either way.
     */
    private static Map<String, String> parseSiteCredentials(String[] entries) {
        if (entries == null) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String entry : entries) {
            // Limit 4: the password is the rest of the string and may contain colons itself.
            String[] parts = entry.split(":", 4);
            if (parts.length < 4) {
                continue;                       // configureHttpClient warns about the format
            }
            String encoded = Base64.getEncoder().encodeToString(
                    (parts[2] + ":" + parts[3]).getBytes(StandardCharsets.UTF_8));
            parsed.put((parts[0] + ":" + parts[1]).toLowerCase(Locale.ROOT), "Basic " + encoded);
        }
        return Map.copyOf(parsed);
    }

    /** The credential configured for this request's target, or null. */
    private String siteCredentialFor(Request clientToProxyRequest) {
        if (siteCredentials.isEmpty()) {
            return null;
        }
        HttpURI uri = clientToProxyRequest.getHttpURI();
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = HttpScheme.HTTPS.is(uri.getScheme()) ? 443 : 80;
        }
        return siteCredentials.get((host + ":" + port).toLowerCase(Locale.ROOT));
    }

    public void setDnsResolver(CustomDnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    public void setPacPolicy(com.testingbot.tunnel.pac.PacPolicy pacPolicy) {
        this.pacPolicy = pacPolicy;
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

    /** How long to wait for a TCP connection to an origin; null keeps jetty-client's default. */
    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }


    /** Wires jetty-client's SPNEGO support to the same settings the other paths use. */
    private void configureNegotiate(HttpClient client, ProxySpec spec) {
        try {
            URI proxyUri = new URI("http://" + spec.getHost() + ":" + spec.getPort());
            org.eclipse.jetty.client.SPNEGOAuthentication spnego =
                    new org.eclipse.jetty.client.SPNEGOAuthentication(proxyUri);
            // Host-based service name; jetty appends the host itself.
            String service = negotiateServiceName;
            if (service != null) {
                spnego.setServiceName(service);
            }
            if (krb5KeyTab != null) {
                spnego.setUserKeyTabPath(krb5KeyTab);
                spnego.setUserName(krb5Principal);
                spnego.setUseTicketCache(false);
            } else {
                spnego.setUseTicketCache(true);
            }
            client.getAuthenticationStore().addAuthentication(spnego);
            LOG.log(Level.INFO, "Upstream proxy authentication: Negotiate (SPN {0})",
                    proxyAuthenticator.servicePrincipalFor(spec.getHost()));
        } catch (URISyntaxException ex) {
            LOG.log(Level.WARNING, "Could not configure Negotiate for the upstream proxy", ex);
        }
    }

    public void setProxyAuthenticator(ProxyAuthenticator authenticator) {
        this.proxyAuthenticator = authenticator == null ? ProxyAuthenticator.none() : authenticator;
    }

    /** Kerberos settings jetty-client needs directly; the authenticator covers the other paths. */
    /**
     * The hosts that may be sent SPNEGO credentials directly, as opposed to the upstream proxy.
     *
     * <p>The SpnegoClient is built with a null service principal so it derives {@code HTTP/host}
     * per request: one list can cover several hosts, each needing its own ticket.
     */
    public void setNegotiateHosts(NegotiateHosts negotiateHosts, java.nio.file.Path keyTab,
                                  String principal) {
        this.negotiateHosts = negotiateHosts == null ? NegotiateHosts.none() : negotiateHosts;
        this.originSpnego = this.negotiateHosts.isEmpty()
                ? null : new SpnegoClient(null, keyTab, principal);
    }

    /**
     * A {@code Negotiate} value for this request's target, or null when the host was not named.
     *
     * <p>A fresh token every time: SPNEGO tokens carry a timestamp and a sequence number, and
     * replaying one is what a service is meant to reject.
     */
    private String negotiateCredentialFor(Request clientToProxyRequest) {
        if (originSpnego == null) {
            return null;
        }
        String host = clientToProxyRequest.getHttpURI().getHost();
        if (host == null || !negotiateHosts.includes(host)) {
            return null;
        }
        try {
            return "Negotiate " + originSpnego.initialToken(host);
        } catch (Exception ex) {
            // The request still goes out; the host answers 401 and says so. Failing the request
            // here would turn a credential problem into an unexplained proxy error.
            LOG.log(Level.WARNING,
                    "Could not obtain a Kerberos token for {0}: {1}. Run --doctor for details.",
                    new Object[]{host, ex.getMessage()});
            return null;
        }
    }

    public void setKerberos(String serviceName, java.nio.file.Path keyTab, String principal) {
        this.negotiateServiceName = serviceName;
        this.krb5KeyTab = keyTab;
        this.krb5Principal = principal;
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
        if (connectTimeoutMs > 0) {
            client.setConnectTimeout(connectTimeoutMs);
        }

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
                        // Wrap the promise rather than the resolver: the platform resolver is
                        // what produces the addresses, so the check has to sit between it and
                        // the dial, on what it actually returned.
                        platformResolver().resolve(target.host(), target.port(), context,
                                new org.eclipse.jetty.util.Promise<List<InetSocketAddress>>() {
                                    @Override
                                    public void succeeded(List<InetSocketAddress> addresses) {
                                        try {
                                            promise.succeeded(refuseLoopback(target.host(), addresses));
                                        } catch (Throwable denied) {
                                            promise.failed(denied);
                                        }
                                    }

                                    @Override
                                    public void failed(Throwable x) {
                                        promise.failed(x);
                                    }
                                });
                        return;
                    }
                    client.getExecutor().execute(() -> {
                        try {
                            List<InetSocketAddress> resolved = new ArrayList<>();
                            for (InetAddress address : dnsResolver.resolve(target.host())) {
                                resolved.add(new InetSocketAddress(address, target.port()));
                            }
                            promise.succeeded(refuseLoopback(target.host(), resolved));
                        } catch (Throwable x) {
                            promise.failed(x);
                        }
                    });
                }

                /**
                 * Enforces {@code --localhost-policy} on the addresses about to be dialled.
                 *
                 * <p>Every address, not just the first: jetty-client falls through the list, so
                 * letting a loopback entry ride along behind a routable one would still reach
                 * loopback whenever the first failed to connect.
                 *
                 * @throws LocalhostPolicy.Denied if any resolved address is loopback
                 */
                private List<InetSocketAddress> refuseLoopback(String host,
                                                               List<InetSocketAddress> addresses) {
                    if (addresses != null) {
                        for (InetSocketAddress address : addresses) {
                            if (localhostPolicy.blocksAddress(address.getAddress())) {
                                LOG.log(Level.INFO, "Localhost policy: refusing dial to {0} ({1})",
                                        new Object[]{host, address.getAddress().getHostAddress()});
                                throw new LocalhostPolicy.Denied(host, address.getAddress());
                            }
                        }
                    }
                    return addresses;
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
            if (proxyAuthenticator.isNegotiate() && !spec.isSocks5()) {
                // Jetty's client already knows the 407 -> Negotiate -> retry dance, so the
                // plain-HTTP path uses that rather than the pre-emptive header the CONNECT
                // path has to send. Same credentials either way.
                configureNegotiate(client, spec);
            } else if (upstreamProxyAuth != null && !upstreamProxyAuth.isEmpty()) {
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

        if (isLoop(request, targetHost)) {
            // One such request otherwise re-enters this handler until the accumulated
            // Via/X-Forwarded-* headers overflow the buffer -- measured at 35 invocations and 34
            // sockets left open, from a single request. A port scanner or a health check aimed
            // at the proxy port is enough to trigger it.
            LOG.log(Level.WARNING, "Refusing request that targets the proxy itself: {0}",
                    request.getHttpURI());
            TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, "508").inc();
            TunnelMetrics.ERRORS_TOTAL.labels("loop_detected").inc();
            Statistics.addRequest();
            ProxyErrors.write(request, response, callback, ProxyErrors.Reason.LOOP_DETECTED);
            return true;
        }

        if (localhostPolicy.blocks(targetHost, dnsResolver == null ? null : dnsResolver::resolve)) {
            LOG.log(Level.INFO, "Localhost policy: rejecting {0}", targetHost);
            TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, "403").inc();
            TunnelMetrics.ERRORS_TOTAL.labels("denied_localhost").inc();
            Statistics.addRequest();
            ProxyErrors.write(request, response, callback, ProxyErrors.Reason.DENIED_LOCALHOST);
            return true;
        }

        if (!allowedHosts.permits(targetHost)) {
            LOG.log(Level.INFO, "Not in --allow-hosts: rejecting {0}", targetHost);
            TunnelMetrics.HTTP_REQUESTS_TOTAL.labels(method, "403").inc();
            TunnelMetrics.ERRORS_TOTAL.labels("not_allowed").inc();
            Statistics.addRequest();
            ProxyErrors.write(request, response, callback, ProxyErrors.Reason.NOT_ALLOWED);
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
            // The per-request line now comes from HttpLogHandler, which sees CONNECT and
            // WebSocket traffic too and can be turned up or down with --log-http.
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
                        // Deliberately no Request.timeout(): that is the TOTAL length of the
                        // request/response conversation, not an idle timeout, so it aborts a
                        // perfectly healthy large download purely for taking a while. A tunnel
                        // carries whatever the site under test serves, including multi-hundred-
                        // megabyte artefacts. Stalls are bounded by the client's idle timeout,
                        // configured in configureHttpClient, which is the right tool for
                        // "a response that trickles forever".
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

    /**
     * True when forwarding this request would send it straight back to this proxy.
     *
     * <p>Two independent checks. The destination matching the address the request arrived on
     * catches it on the first hop; a Via header already naming us catches anything that reaches
     * us by another route, which is what RFC 9110 defines Via for.
     */
    private boolean isLoop(Request request, String targetHost) {
        for (String via : request.getHeaders().getValuesList(HttpHeader.VIA)) {
            if (via != null && via.contains(getViaHost())) {
                return true;
            }
        }

        int targetPort = request.getHttpURI().getPort();
        if (targetPort <= 0) {
            targetPort = HttpScheme.HTTPS.is(request.getHttpURI().getScheme()) ? 443 : 80;
        }
        if (targetPort != Request.getLocalPort(request)) {
            return false;
        }
        // Same port as ours: only a loop if it also names this machine.
        return LocalhostPolicy.DENY.blocks(targetHost);
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
            String upstreamAuthorization = upstreamAuthorization();
            if (upstreamAuthorization != null) {
                fields.put(HttpHeader.PROXY_AUTHORIZATION, upstreamAuthorization);
            }
            // Site credentials, sent pre-emptively. Never over one the client supplied: it
            // meant what it sent, and overwriting it would be a surprising thing for a proxy
            // to do. Negotiate wins over --auth for a host named in both, because naming a
            // host under --krb5-hosts is the more specific statement of intent.
            if (!fields.contains(HttpHeader.AUTHORIZATION)) {
                String negotiate = negotiateCredentialFor(clientToProxyRequest);
                String siteCredential = negotiate != null
                        ? negotiate : siteCredentialFor(clientToProxyRequest);
                if (siteCredential != null) {
                    fields.put(HttpHeader.AUTHORIZATION, siteCredential);
                }
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
            // Pass the correlation id on, so the origin's own logs can be lined up with ours.
            Object requestId = clientToProxyRequest.getAttribute(HttpLogHandler.ATTR_REQUEST_ID);
            if (requestId != null) {
                fields.put(requestIdHeader, requestId.toString());
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
