package com.testingbot.tunnel;

import io.prometheus.client.servlet.jakarta.exporter.MetricsServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InsightServer {
    public InsightServer(App app) {
        // Make sure all collectors are registered before any scrape arrives.
        TunnelMetrics.init();

        Server server = new Server(app.getMetricsPort());

        // Using ServletContextHandler for Jetty 11
        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        server.setHandler(handler);

        // Legacy JSON status at /
        handler.addServlet(JsonServlet.class, "");
        handler.addServlet(JsonServlet.class, "/");

        // Prometheus exposition at /metrics
        ServletHolder metricsHolder = new ServletHolder(new MetricsServlet());
        handler.addServlet(metricsHolder, "/metrics");

        String auth = app.getMetricsAuth();
        if (auth != null && !auth.isEmpty()) {
            FilterHolder filterHolder = new FilterHolder(new BasicAuthFilter(auth));
            handler.addFilter(filterHolder, "/metrics", EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        }

        try {
            server.start();
        } catch (Exception ex) {
            Logger.getLogger(InsightServer.class.getName()).log(Level.SEVERE,
                "Could not set up metrics service. Make sure port " + app.getMetricsPort()
                    + " is available or change with --metrics-port. /metrics (Prometheus) and / (JSON) will be unavailable.",
                ex);
        }
    }

    public static class JsonServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            TunnelMetrics.refreshUptime(Statistics.getStartTime());
            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println("{\"version\":\"" + App.VERSION + "\", \"uptime\":\"" + (System.currentTimeMillis() - Statistics.getStartTime()) + "\","
                + "\"numberOfRequests\":\"" + Statistics.getNumberOfRequests() + "\", \"bytesTransferred\":" + Statistics.getBytesTransferred() + "}");
        }
    }

    /**
     * Minimal HTTP Basic auth filter for the /metrics endpoint.
     * Compares against the user:password value passed via --metrics-auth.
     */
    static final class BasicAuthFilter implements Filter {
        private final String expectedHeader;

        BasicAuthFilter(String userColonPassword) {
            this.expectedHeader = "Basic " + Base64.getEncoder()
                .encodeToString(userColonPassword.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void init(FilterConfig filterConfig) {
            // no-op
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;

            String header = req.getHeader("Authorization");
            if (header == null || !constantTimeEquals(header, expectedHeader)) {
                resp.setHeader("WWW-Authenticate", "Basic realm=\"testingbot-tunnel\"");
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
            // no-op
        }

        private static boolean constantTimeEquals(String a, String b) {
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
