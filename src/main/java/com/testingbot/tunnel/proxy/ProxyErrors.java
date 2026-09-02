package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.TunnelMetrics;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Turns a proxying failure into an answer a user can act on.
 *
 * <p>Every failure used to surface as a bare 502, which says only "something upstream went
 * wrong" — the single most common tunnel support ticket is someone asking which thing. A
 * classified reason travels three ways: the HTTP status, an {@code X-TestingBot-Error} header
 * for machines, and a one-line body for humans reading a browser window. The same string is
 * the {@code reason} label on {@link TunnelMetrics#PROXY_ERRORS_TOTAL}, so a spike on a
 * dashboard and a header in a bug report name the same thing.
 */
public final class ProxyErrors {

    /** Header carrying the machine-readable reason. */
    public static final String ERROR_HEADER = "X-TestingBot-Error";

    private ProxyErrors() {
    }

    /** A classified failure: stable reason string, HTTP status, and a human explanation. */
    public enum Reason {
        DNS_ERROR("dns-error", HttpStatus.BAD_GATEWAY_502,
                "The hostname could not be resolved from the machine running the tunnel."),
        CONNECTION_REFUSED("connection-refused", HttpStatus.BAD_GATEWAY_502,
                "The target refused the connection. Is the service listening on that port?"),
        CONNECTION_TIMEOUT("connection-timeout", HttpStatus.GATEWAY_TIMEOUT_504,
                "Timed out connecting to the target."),
        HOST_UNREACHABLE("host-unreachable", HttpStatus.BAD_GATEWAY_502,
                "No network route to the target from the machine running the tunnel."),
        TLS_ERROR("tls-error", HttpStatus.BAD_GATEWAY_502,
                "TLS handshake with the target failed."),
        UPSTREAM_PROXY_UNREACHABLE("upstream-proxy-unreachable", HttpStatus.BAD_GATEWAY_502,
                "Could not reach the upstream proxy configured with --proxy."),
        UPSTREAM_PROXY_AUTH_FAILED("upstream-proxy-auth-failed", HttpStatus.BAD_GATEWAY_502,
                "The upstream proxy rejected the credentials from --proxy-userpwd."),
        /**
         * The proxy answered and said no -- a 403 from its own allow list, or a SOCKS reply
         * other than success.
         *
         * <p>Distinct from UNREACHABLE, which these used to be reported as: an operator told the
         * proxy could not be reached goes and checks connectivity, when the connection worked
         * perfectly and the proxy's policy is what refused the destination.
         */
        UPSTREAM_PROXY_REFUSED("upstream-proxy-refused", HttpStatus.BAD_GATEWAY_502,
                "The upstream proxy was reached but refused this destination."),
        MALFORMED_REQUEST_URI("malformed-request-uri", HttpStatus.BAD_REQUEST_400,
                "The request URI is malformed and was not forwarded."),
        DENIED_BY_FAST_FAIL("denied-by-fast-fail", HttpStatus.FORBIDDEN_403,
                "Blocked by the --fast-fail-regexps policy."),
        NOT_ALLOWED("not-allowed", HttpStatus.FORBIDDEN_403,
                "This host is not in the --allow-hosts list, so the tunnel may not reach it."),
        DENIED_LOCALHOST("denied-localhost", HttpStatus.FORBIDDEN_403,
                "Blocked by --localhost-policy deny: the tunnel may not reach this machine's "
                + "loopback interface."),
        LOOP_DETECTED("loop-detected", HttpStatus.LOOP_DETECTED_508,
                "The request targets this proxy itself, which would forward it back to itself."),
        TUNNEL_ERROR("tunnel-error", HttpStatus.BAD_GATEWAY_502,
                "The tunnel could not complete the request.");

        private final String reason;
        private final int status;
        private final String explanation;

        Reason(String reason, int status, String explanation) {
            this.reason = reason;
            this.status = status;
            this.explanation = explanation;
        }

        public String reason() {
            return reason;
        }

        public int status() {
            return status;
        }

        public String explanation() {
            return explanation;
        }
    }

    /**
     * Maps a failure to a reason.
     *
     * <p>Walks the cause chain: the interesting exception is usually wrapped by the time it
     * reaches a handler. Falls back to {@link Reason#TUNNEL_ERROR}, which is no worse than the
     * bare 502 that used to be the only outcome.
     */
    public static Reason classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            Reason direct = classifyOne(t);
            if (direct != null) {
                return direct;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Reason.TUNNEL_ERROR;
    }

    private static Reason classifyOne(Throwable t) {
        if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
            return Reason.DNS_ERROR;
        }
        if (t instanceof NoRouteToHostException) {
            return Reason.HOST_UNREACHABLE;
        }
        if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
            return Reason.CONNECTION_TIMEOUT;
        }
        if (t instanceof SSLException) {
            return Reason.TLS_ERROR;
        }
        if (t instanceof IllegalArgumentException) {
            return Reason.MALFORMED_REQUEST_URI;
        }
        if (t instanceof PortUnreachableException) {
            return Reason.HOST_UNREACHABLE;
        }
        if (t instanceof ConnectException) {
            // ConnectException covers both refusal and timeout; only the message separates them.
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("timed out") || message.contains("timeout")) {
                return Reason.CONNECTION_TIMEOUT;
            }
            return Reason.CONNECTION_REFUSED;
        }
        if (t instanceof IOException) {
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            // Raised by our own upstream-proxy handshakes (HTTP CONNECT and SOCKS5), which
            // report failures as IOException with a descriptive message.
            if (message.contains("proxy") && (message.contains("credential")
                    || message.contains("authentication") || message.contains("407"))) {
                return Reason.UPSTREAM_PROXY_AUTH_FAILED;
            }
            // A refusal is not a connectivity problem, and is checked first: the proxy answered.
            if (message.contains("rejected connect") || message.contains("refused connect")) {
                return Reason.UPSTREAM_PROXY_REFUSED;
            }
            if (message.contains("upstream proxy") || message.contains("socks")) {
                return Reason.UPSTREAM_PROXY_UNREACHABLE;
            }
        }
        return null;
    }

    /**
     * Writes a classified error response and records the reason.
     *
     * <p>Safe to call for CONNECT as well as ordinary requests: it runs before any tunnel is
     * established, so the response is still an ordinary HTTP message.
     */
    public static void write(Request request, Response response, Callback callback, Reason reason) {
        TunnelMetrics.PROXY_ERRORS_TOTAL.labels(reason.reason()).inc();
        response.getHeaders().put(ERROR_HEADER, reason.reason());
        String message = reason.reason() + ": " + reason.explanation();

        // Jetty's ErrorHandler only writes a body for GET, POST, HEAD and BAD
        // (ErrorHandler.ERROR_METHODS), so for a CONNECT the promised one-line explanation
        // never reached the client: the 502 arrived with the header and an empty body, and a
        // curl user saw a bare status line. Written here instead.
        if (org.eclipse.jetty.http.HttpMethod.CONNECT.is(request.getMethod())) {
            byte[] body = (message + System.lineSeparator())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            response.setStatus(reason.status());
            response.getHeaders().put(org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE,
                    "text/plain;charset=utf-8");
            response.getHeaders().put(org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH,
                    body.length);
            response.write(true, java.nio.ByteBuffer.wrap(body), callback);
            return;
        }
        Response.writeError(request, response, callback, reason.status(), message);
    }

    /** Classifies {@code failure} and writes the resulting response. */
    public static Reason writeFor(Request request, Response response, Callback callback,
                                  Throwable failure) {
        Reason reason = classify(failure);
        write(request, response, callback, reason);
        return reason;
    }
}
