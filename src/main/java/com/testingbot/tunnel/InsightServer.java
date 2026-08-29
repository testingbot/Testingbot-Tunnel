package com.testingbot.tunnel;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.util.Callback;

/**
 * Serves tunnel status: a small JSON document at {@code /} and Prometheus exposition at
 * {@code /metrics}.
 *
 * <p>Built on Jetty's core Handler API rather than servlets. Two endpoints returning fixed
 * strings do not need a servlet container, and avoiding one keeps the Jakarta EE stack --
 * and the Prometheus servlet bridge -- out of the dependency tree entirely.
 */
public class InsightServer {

    public InsightServer(App app) {
        // Make sure all collectors are registered before any scrape arrives.
        TunnelMetrics.init();

        Server server = new Server();
        org.eclipse.jetty.server.ServerConnector connector =
                new org.eclipse.jetty.server.ServerConnector(server);
        connector.setPort(app.getMetricsPort());
        server.addConnector(connector);

        // On the CONNECTOR, not the Server. Connection.Listener beans are attached to new
        // connections from the connector's event listeners, so a Server-level bean is never
        // consulted and every listener="metrics" series stayed permanently zero.
        ConnectionStatistics metricsStats = new ConnectionStatistics();
        connector.addBean(metricsStats);
        TunnelMetrics.connectionMetrics().add(ConnectionMetrics.METRICS, metricsStats);

        PathMappingsHandler routes = new PathMappingsHandler();
        routes.addMapping(org.eclipse.jetty.http.pathmap.PathSpec.from("/"), new JsonStatusHandler());
        routes.addMapping(org.eclipse.jetty.http.pathmap.PathSpec.from("/metrics"),
                protect(new MetricsHandler(), app.getMetricsAuth()));
        server.setHandler(routes);

        try {
            server.start();
        } catch (Exception ex) {
            Logger.getLogger(InsightServer.class.getName()).log(Level.SEVERE,
                "Could not set up metrics service. Make sure port " + app.getMetricsPort()
                    + " is available or change with --metrics-port. /metrics (Prometheus) and / (JSON) will be unavailable.",
                ex);
        }
    }

    /** Wraps {@code handler} in Basic auth when --metrics-auth was supplied. */
    private static Handler protect(Handler handler, String userColonPassword) {
        if (userColonPassword == null || userColonPassword.isEmpty()) {
            return handler;
        }
        BasicAuthHandler auth = new BasicAuthHandler(userColonPassword);
        auth.setHandler(handler);
        return auth;
    }

    static class JsonStatusHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            TunnelMetrics.refreshUptime(Statistics.getStartTime());
            String body = "{\"version\":\"" + App.VERSION + "\", \"uptime\":\""
                + (System.currentTimeMillis() - Statistics.getStartTime()) + "\","
                + "\"numberOfRequests\":\"" + Statistics.getNumberOfRequests() + "\", "
                + "\"bytesTransferred\":" + Statistics.getBytesTransferred() + "}\n";
            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
            Content.Sink.write(response, true, body, callback);
            return true;
        }
    }

    static class MetricsHandler extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws IOException {
            // Honour the Accept header the same way the Prometheus servlet bridge does, so
            // scrapers negotiating OpenMetrics still get what they asked for.
            // Prometheus scrapes /metrics, not /, so refreshing uptime only on the JSON
            // endpoint left this gauge frozen for the normal setup.
            TunnelMetrics.refreshUptime(Statistics.getStartTime());

            String contentType = TextFormat.chooseContentType(request.getHeaders().get(HttpHeader.ACCEPT));
            StringWriter body = new StringWriter();
            // Honour ?name[]= filtering, which the Prometheus servlet used to support and some
            // scrape configs rely on to fetch a subset.
            java.util.Set<String> requested = new java.util.HashSet<>(
                    org.eclipse.jetty.util.UrlEncoded.decodeQuery(
                            request.getHttpURI().getQuery() == null ? "" : request.getHttpURI().getQuery())
                        .getValues("name[]") == null
                            ? java.util.List.of()
                            : org.eclipse.jetty.util.UrlEncoded.decodeQuery(
                                request.getHttpURI().getQuery()).getValues("name[]"));
            TextFormat.writeFormat(contentType, body, requested.isEmpty()
                    ? CollectorRegistry.defaultRegistry.metricFamilySamples()
                    : CollectorRegistry.defaultRegistry.filteredMetricFamilySamples(requested));

            response.setStatus(HttpStatus.OK_200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
            Content.Sink.write(response, true, body.toString(), callback);
            return true;
        }
    }

    /**
     * Minimal HTTP Basic auth for the /metrics endpoint.
     * Compares against the user:password value passed via --metrics-auth.
     */
    static final class BasicAuthHandler extends Handler.Wrapper {
        private final String expectedHeader;

        BasicAuthHandler(String userColonPassword) {
            this.expectedHeader = "Basic " + Base64.getEncoder()
                .encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String header = request.getHeaders().get(HttpHeader.AUTHORIZATION);
            if (header == null || !constantTimeEquals(header, expectedHeader)) {
                response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"testingbot-tunnel\"");
                Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401);
                return true;
            }
            return super.handle(request, response, callback);
        }

        static boolean constantTimeEquals(String a, String b) {
            byte[] aB = a.getBytes(StandardCharsets.UTF_8);
            byte[] bB = b.getBytes(StandardCharsets.UTF_8);
            if (aB.length != bB.length) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < aB.length; i++) {
                diff |= aB[i] ^ bB[i];
            }
            return diff == 0;
        }
    }
}
