package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import java.util.Arrays;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.client.api.Request;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ForwarderServlet extends AsyncProxyServlet {
    private final App app;

    public ForwarderServlet(App app) {
        this.app = app;
    }

    @Override
    protected String rewriteTarget(HttpServletRequest request) {
        return "http://127.0.0.1:" + app.getSSHPort() + request.getRequestURI();
    }

    @Override
    protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
        super.addProxyHeaders(clientRequest, proxyRequest);

        proxyRequest.header("TB-Tunnel", this.app.getServerIP());
        proxyRequest.header("TB-Tunnel-Version", App.VERSION.toString());
        proxyRequest.header("TB-Credentials", this.app.getClientKey() + "_" + this.app.getClientSecret());
        if (this.app.isBypassingSquid()) {
            proxyRequest.header("TB-Tunnel-Port", "2010");
        }

        if (this.app.getPac() != null) {
            proxyRequest.header("TB-Tunnel-Pac", this.app.getPac());
        }

        Logger.getLogger(ForwarderServlet.class.getName()).log(Level.INFO, "[{0}] {1}", new Object[]{clientRequest.getMethod(), clientRequest.getRequestURL()});

        if (app.isDebugMode()) {
            Enumeration<String> headerNames = clientRequest.getHeaderNames();
            if (headerNames != null) {
                StringBuilder sb = new StringBuilder();
                String header;

                while (headerNames.hasMoreElements()) {
                    header = headerNames.nextElement();
                    sb.append(header).append(": ").append(clientRequest.getHeader(header)).append(System.lineSeparator());
                }
                Logger.getLogger(ForwarderServlet.class.getName()).log(Level.INFO, sb.toString());
            }
        }
    }

    @Override
    protected void onClientRequestFailure(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, Throwable failure) {
        super.onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
        Logger.getLogger(ForwarderServlet.class.getName()).log(Level.WARNING, "Error when forwarding request: {0} {1}", new Object[]{failure.getMessage(), Arrays.toString(failure.getStackTrace())});
    }
}
