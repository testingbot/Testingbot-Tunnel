package com.testingbot.tunnel;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

public class InsightServer {
    public InsightServer(App app) {
        Server server = new Server(app.getMetricsPort());
        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        handler.addServletWithMapping(JsonServlet.class, "/*");

        try {
            server.start();
        } catch (Exception ex) {
            Logger.getLogger(InsightServer.class.getName()).log(Level.SEVERE, null, "Could not set up metrics service. Make sure port " + app.getMetricsPort() + " is available or change with --metrics-port");
        }
    }
    
    @SuppressWarnings("serial")
    public static class JsonServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
                resp.setContentType("application/json");
                resp.setStatus(200);
                resp.getWriter().println("{\"version\":\"" + App.VERSION + "\", \"uptime\":\"" + (System.currentTimeMillis() - Statistics.getStartTime()) + "\","
                        + "\"numberOfRequests:\"" + Statistics.getNumberOfRequests() + "\", \"bytesTransferred\":" + Statistics.getBytesTransfered() + "}");
        }
    }
}
