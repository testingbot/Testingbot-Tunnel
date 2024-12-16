package com.testingbot.tunnel;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InsightServer {
    public InsightServer(App app) {
        Server server = new Server(app.getMetricsPort());

        // Using ServletContextHandler for Jetty 11
        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        server.setHandler(handler);

        // Register the servlet and map it to the root URL pattern
        handler.addServlet(JsonServlet.class, "/*");

        try {
            server.start();
        } catch (Exception ex) {
            Logger.getLogger(InsightServer.class.getName()).log(Level.SEVERE, "Could not set up metrics service. Make sure port " + app.getMetricsPort() + " is available or change with --metrics-port", ex);
        }
    }

    public static class JsonServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println("{\"version\":\"" + App.VERSION + "\", \"uptime\":\"" + (System.currentTimeMillis() - Statistics.getStartTime()) + "\","
                + "\"numberOfRequests\":\"" + Statistics.getNumberOfRequests() + "\", \"bytesTransferred\":" + Statistics.getBytesTransferred() + "}");
        }
    }
}
