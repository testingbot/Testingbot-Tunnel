package com.testingbot.tunnel.proxy;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Structured per-request logging for traffic crossing the tunnel, controlled by
 * {@code --log-http}.
 *
 * <p>Reproducing a flaky test means knowing exactly what the cloud browser asked for. A single
 * INFO line per request was all we had, with no way to raise or lower the detail and no way to
 * tie a request to the response it produced.
 *
 * <p>Sits outermost in the proxy chain, so plain HTTP, HTTPS CONNECT and WebSocket upgrades are
 * all covered by the same switch and the same correlation id.
 *
 * <p>Header values pass through {@link SensitiveHeaders#redactValue}: at {@code headers} level
 * these lines would otherwise carry {@code Authorization} and TestingBot credentials into
 * whatever collects the customer's logs.
 */
public class HttpLogHandler extends Handler.Wrapper {

    private static final Logger LOG = Logger.getLogger(HttpLogHandler.class.getName());

    /** Where the correlation id is published for the rest of the chain. */
    public static final String ATTR_REQUEST_ID = HttpLogHandler.class.getName() + ".requestId";

    public enum Mode {
        /** Log nothing. */
        NONE,
        /** One line per request: method, target, status, duration. */
        URL,
        /** As {@link #URL}, plus the request headers. */
        HEADERS,
        /** As {@link #HEADERS}, but only for requests that failed or answered 5xx. */
        ERRORS,
        /**
         * As {@link #HEADERS}, plus the request body, redacted by
         * {@link BodyRedactor}.
         *
         * <p>Only meaningful for the Selenium relay, whose bodies are small structured JSON.
         * {@code LogHttpPolicy} refuses it for browser traffic, which would have to be buffered
         * to be logged and must stream.
         */
        BODY;

        /**
         * True when this mode prints the request headers as well as the request line.
         *
         * <p>Asked rather than compared against HEADERS by name: BODY is "as HEADERS, plus the
         * body", so a {@code mode == HEADERS} test made the most verbose level print strictly
         * less than the one below it.
         */
        public boolean includesHeaders() {
            return this == HEADERS || this == ERRORS || this == BODY;
        }

        public static Mode parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                return ERRORS;
            }
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private final Mode mode;
    private final String requestIdHeader;

    public HttpLogHandler(Mode mode, String requestIdHeader) {
        this.mode = mode == null ? Mode.ERRORS : mode;
        this.requestIdHeader = requestIdHeader == null || requestIdHeader.isEmpty()
                ? "X-Request-Id" : requestIdHeader;
    }

    public String getRequestIdHeader() {
        return requestIdHeader;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        Handler next = getHandler();
        if (next == null) {
            return false;
        }
        // Reuse the caller's id when there is one, so a trace that starts in the test framework
        // stays joined up through the tunnel rather than restarting here.
        String existing = request.getHeaders().get(requestIdHeader);
        String requestId = existing != null && !existing.isBlank()
                ? existing.trim()
                : newRequestId();
        request.setAttribute(ATTR_REQUEST_ID, requestId);

        if (mode == Mode.NONE) {
            return next.handle(request, response, callback);
        }

        long startedAt = System.nanoTime();
        Callback observed = new Callback() {
            @Override
            public void succeeded() {
                log(request, response, requestId, startedAt, null);
                callback.succeeded();
            }

            @Override
            public void failed(Throwable failure) {
                log(request, response, requestId, startedAt, failure);
                callback.failed(failure);
            }
        };
        return next.handle(request, response, observed);
    }

    private void log(Request request, Response response, String requestId, long startedAt,
                     Throwable failure) {
        int status = response.getStatus();
        boolean failed = failure != null || status >= 500;
        // BODY is refused for this module, so it can only arrive here by mistake; treat it as
        // HEADERS rather than silently logging nothing.
        if (mode == Mode.ERRORS && !failed) {
            return;
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        StringBuilder line = new StringBuilder()
                .append('[').append(requestId).append("] ")
                .append(request.getMethod()).append(' ')
                .append(target(request))
                .append(" -> ").append(status)
                .append(" (").append(elapsedMs).append(" ms)");
        if (failure != null) {
            line.append(" failed: ").append(failure);
        }

        if (mode == Mode.HEADERS || mode == Mode.ERRORS) {
            for (HttpField field : request.getHeaders()) {
                line.append(System.lineSeparator())
                        .append("    ").append(field.getName()).append(": ")
                        .append(SensitiveHeaders.redactValue(field.getName(), field.getValue()));
            }
        }
        LOG.log(failed ? Level.WARNING : Level.INFO, line.toString());
    }

    /** CONNECT carries an authority rather than a URL; log whichever the request actually has. */
    private static String target(Request request) {
        String authority = request.getHttpURI().getAuthority();
        if (request.getHttpURI().getPath() == null && authority != null) {
            return authority;
        }
        return request.getHttpURI().toString();
    }

    private static String newRequestId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
    }
}
