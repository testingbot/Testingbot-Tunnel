package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.Statistics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.ProxyAuthenticationProtocolHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.util.Callback;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TunnelProxyServlet extends AsyncProxyServlet {
    private String proxyAuthHeaderValue = null;

    class TunnelProxyResponseListener extends ProxyResponseListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        public long startTime = System.currentTimeMillis();

        protected TunnelProxyResponseListener(HttpServletRequest request, HttpServletResponse response) {
            super(request, response);
            this.request = request;
            this.response = response;
        }

        @Override
        public void onBegin(Response proxyResponse) {
            startTime = System.currentTimeMillis();
            super.onBegin(proxyResponse);
        }

        @Override
        public void onComplete(Result result) {
            long endTime = System.currentTimeMillis();
            Statistics.addRequest();

            Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.INFO, "[{0}] {1} ({2}) - {3}", new Object[]{request.getMethod(), request.getRequestURL().toString(), response.toString().substring(9, 12), (endTime - startTime) + " ms"});
            if (getServletConfig().getInitParameter("tb_debug") != null) {
                Enumeration<String> headerNames = request.getHeaderNames();
                if (headerNames != null) {
                    StringBuilder sb = new StringBuilder();
                    String header;

                    while (headerNames.hasMoreElements()) {
                        header = headerNames.nextElement();
                        sb.append(header).append(": ").append(request.getHeader(header)).append(System.lineSeparator());
                    }

                    Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.INFO, sb.toString());
                }
            }
            if (result.isFailed() && !request.getRequestURL().toString().contains("squid-internal")) {
                Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.SEVERE, "Local proxy received a connection failure from upstream. Make sure the website you want to test is accessible from this machine {0}.", new Object[]{result.getResponseFailure()});
            }
            super.onComplete(result);
        }
    }

    @Override
    protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback) {
        Statistics.addBytesTransferred(length);
        super.onResponseContent(request, response, proxyResponse, buffer, offset, length, callback);
    }

    @Override
    protected void onClientRequestFailure(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, Throwable failure) {
        if (!clientRequest.getRequestURL().toString().contains("squid-internal")) {
            StringWriter sw = new StringWriter();
            failure.printStackTrace(new PrintWriter(sw));
            Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.WARNING, "{0} for request {1}\n{2}", new Object[]{failure.getMessage(), clientRequest.getMethod() + " - " + clientRequest.getRequestURL().toString(), sw.toString()});
        }

        super.onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
    }

    @Override
    protected Response.Listener newProxyResponseListener(HttpServletRequest request, HttpServletResponse response) {
        return new TunnelProxyResponseListener(request, response);
    }

    @Override
    protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
        super.addProxyHeaders(clientRequest, proxyRequest);

        if (proxyAuthHeaderValue != null) {
            proxyRequest.header(HttpHeader.PROXY_AUTHORIZATION, proxyAuthHeaderValue);
        }

        Object extraHeadersAttr = getServletContext().getAttribute("extra_headers");
        if (extraHeadersAttr instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) extraHeadersAttr;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                proxyRequest.header(key, value);
            }
        }
    }

    @Override
    protected HttpClient newHttpClient() {
        HttpClient client = new HttpClient();
        AuthenticationStore auth;

        final String proxy = getServletConfig().getInitParameter("proxy");
        if (proxy != null && !proxy.isEmpty()) {
            String[] splitted = proxy.split(":", 2);
            if (splitted.length < 2) {
                Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.WARNING, "Invalid proxy format, expected host:port");
            } else {
                ProxyConfiguration proxyConfig = client.getProxyConfiguration();
                HttpProxy httpProxy = new HttpProxy(splitted[0], Integer.parseInt(splitted[1]));
                proxyConfig.getProxies().add(httpProxy);

                String proxyAuth = getServletConfig().getInitParameter("proxyAuth");
                if (proxyAuth != null && !proxyAuth.isEmpty()) {
                    String[] credentials = proxyAuth.split(":", 2);
                    if (credentials.length >= 2) {
                        Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.INFO, "Proxy authentication configured");

                        String userPass = credentials[0] + ":" + credentials[1];
                        proxyAuthHeaderValue = "Basic " + java.util.Base64.getEncoder().encodeToString(userPass.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
        }

        final String basicAuthString = getServletConfig().getInitParameter("basicAuth");
        if (basicAuthString != null) {
            final String[] basicAuth = basicAuthString.split(",");
            for (String authCredentials : basicAuth) {
                String[] credentials = authCredentials.split(":");
                if (credentials.length < 4) {
                    Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.WARNING, "Invalid basic auth format, expected host:port:user:password");
                    continue;
                }
                auth = client.getAuthenticationStore();
                Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.INFO, "Adding Basic Auth for {0}:{1}", new Object[]{credentials[0], credentials[1]});
                try {
                    auth.addAuthentication(new BasicAuthentication(new URI("http://" + credentials[0] + ":" + credentials[1]), Authentication.ANY_REALM, credentials[2], credentials[3]));
                } catch (URISyntaxException ex) {
                    Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.SEVERE, "Invalid URI for basic auth", ex);
                }
            }
        }

        return client;
    }
}
