package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import org.eclipse.jetty.proxy.AsyncProxyServlet;

import java.util.Enumeration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Request;

public class ForwarderServlet extends AsyncProxyServlet {
    private App app;
    
    public ForwarderServlet(App app) {
        this.app = app;
    }
    
    @Override
    protected String rewriteTarget(HttpServletRequest request) {   
        return "http://127.0.0.1:4446" + request.getRequestURI();
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
       
        Logger.getLogger(ForwarderServlet.class.getName()).log(Level.INFO, " >> [{0}] {1}", new Object[]{clientRequest.getMethod(), clientRequest.getRequestURL()});
        if (app.isDebugMode()) {
            Enumeration<String> headerNames = clientRequest.getHeaderNames();
             if (headerNames != null) {
                StringBuilder sb = new StringBuilder();
                String header;
 
                while (headerNames.hasMoreElements()) {
                    header = headerNames.nextElement();
                    sb.append(header).append(": ").append(clientRequest.getHeader(header)).append(System.getProperty("line.separator"));
                }
                Logger.getLogger(ForwarderServlet.class.getName()).log(Level.INFO, sb.toString());
            }
        }
    }

    @Override
    protected void onClientRequestFailure(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, Throwable failure)
    {
        super.onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
        Logger.getLogger(ForwarderServlet.class.getName()).log(Level.WARNING, "Error when forwarding request: {0} {1}", new Object[]{failure.getMessage(), failure.getStackTrace().toString()});
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
        }
        
        return client;
    }
}
