package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import java.net.URI;
import java.net.URISyntaxException;
import org.eclipse.jetty.proxy.AsyncProxyServlet;

import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class ForwarderServlet extends AsyncProxyServlet {
    private App app;
    
    public ForwarderServlet(App app) {
        this.app = app;
    }
    
    @Override
    protected String rewriteTarget(HttpServletRequest request) {   
        return "http://127.0.0.1:4446" + request.getRequestURI();
    }
    
    protected HttpClient createHttpClient() throws ServletException
    {
        ServletConfig config = getServletConfig();

        HttpClient client = newHttpClient();

        // Redirects must be proxied as is, not followed.
        client.setFollowRedirects(false);

        // Must not store cookies, otherwise cookies of different clients will mix.
        client.setCookieStore(new HttpCookieStore.Empty());

        Executor executor;
        String value = config.getInitParameter("maxThreads");
        if (value == null || "-".equals(value))
        {
            executor = (Executor)getServletContext().getAttribute("org.eclipse.jetty.server.Executor");
            if (executor==null)
                throw new IllegalStateException("No server executor for proxy");
        }
        else
        {
            QueuedThreadPool qtp= new QueuedThreadPool(Integer.parseInt(value));
            String servletName = config.getServletName();
            int dot = servletName.lastIndexOf('.');
            if (dot >= 0)
                servletName = servletName.substring(dot + 1);
            qtp.setName(servletName);
            executor=qtp;
        }

        client.setExecutor(executor);

        value = config.getInitParameter("maxConnections");
        if (value == null)
            value = "256";
        client.setMaxConnectionsPerDestination(Integer.parseInt(value));

        value = config.getInitParameter("idleTimeout");
        if (value == null)
            value = "30000";
        client.setIdleTimeout(Long.parseLong(value));

        value = config.getInitParameter("timeout");
        if (value == null)
            value = "60000";
        setTimeout(Long.parseLong(value));

        value = config.getInitParameter("requestBufferSize");
        if (value != null)
            client.setRequestBufferSize(Integer.parseInt(value));

        value = config.getInitParameter("responseBufferSize");
        if (value != null)
            client.setResponseBufferSize(Integer.parseInt(value));

        try
        {
            client.start();

            // Content must not be decoded, otherwise the client gets confused.
            client.getContentDecoderFactories().clear();

            return client;
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }
    
    @Override
    protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest)
    {
        super.addProxyHeaders(clientRequest, proxyRequest);
        
        proxyRequest.header("TB-Tunnel", this.app.getServerIP());
        proxyRequest.header("TB-Credentials", this.app.getClientKey() + "_" + this.app.getClientSecret());
        if (this.app.isBypassingSquid()) {
            proxyRequest.header("TB-Tunnel-Port", "2010");
        }
        
        if (this.app.getCustomHeaders().size() > 0) {
            for (Map.Entry<String, String> entry : this.app.getCustomHeaders().entrySet()) {
                proxyRequest.header(entry.getKey(), entry.getValue());
            }
        }
       
        Logger.getLogger(App.class.getName()).log(Level.INFO, ">> [{0}] {1}", new Object[]{clientRequest.getMethod(), clientRequest.getRequestURL()});
        if (app.isDebugMode()) {
            Enumeration<String> headerNames = clientRequest.getHeaderNames();
             if (headerNames != null) {
                StringBuilder sb = new StringBuilder();
                String header;
 
                while (headerNames.hasMoreElements()) {
                    header = headerNames.nextElement();
                    sb.append(header).append(": ").append(clientRequest.getHeader(header)).append(System.getProperty("line.separator"));
                }
                Logger.getLogger(App.class.getName()).log(Level.INFO, sb.toString());
            }
        }
    }

    @Override
    protected void onClientRequestFailure(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, Throwable failure)
    {
        super.onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
        Logger.getLogger(App.class.getName()).log(Level.WARNING, "Error when forwarding request: {0} {1}", new Object[]{failure.getMessage(), failure.getStackTrace().toString()});
    }
    
    @Override
    protected HttpClient newHttpClient()
    {
        HttpClient client = new HttpClient();
        
        String proxy = getServletConfig().getInitParameter("proxy");
        if (proxy != null && !proxy.isEmpty())
        {
            String[] splitted = proxy.split(":");
            ProxyConfiguration proxyConfig = client.getProxyConfiguration();
            proxyConfig.getProxies().add(new HttpProxy(splitted[0], Integer.parseInt(splitted[1])));
            
            String proxyAuth = getServletConfig().getInitParameter("proxyAuth");
            if (proxyAuth != null && !proxyAuth.isEmpty())
            {
                String[] credentials = proxyAuth.split(":");
                
                AuthenticationStore auth = client.getAuthenticationStore();
                try {
                    auth.addAuthentication(new BasicAuthentication(new URI("http://" + proxy), Authentication.ANY_REALM, credentials[0], credentials[1]));
                } catch (URISyntaxException ex) {
                    Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
        return client;
    }
}
