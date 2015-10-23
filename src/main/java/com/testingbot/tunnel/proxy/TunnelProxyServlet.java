package com.testingbot.tunnel.proxy;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.proxy.AsyncProxyServlet;


public class TunnelProxyServlet extends AsyncProxyServlet {
    
    class TunnelProxyResponseListener extends ProxyResponseListener
    {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        public long startTime = System.currentTimeMillis();
        
        protected TunnelProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
        {
            super(request, response);
            this.request = request;
            this.response = response;
        }
        
        @Override
        public void onBegin(Response proxyResponse)
        {
            startTime = System.currentTimeMillis();
            super.onBegin(proxyResponse);
        }
        
        @Override
        public void onComplete(Result result)
        {
            long endTime = System.currentTimeMillis();
            Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.INFO, "<< [{0}] {1} ({2}) - {3}", new Object[]{request.getMethod(), request.getRequestURL().toString(), response.toString().substring(9, 12), (endTime-this.startTime) + " ms"});
            super.onComplete(result);
        }
    }
    
    
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }
    
    @Override
    protected void onClientRequestFailure(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, Throwable failure)
    {
        if (clientRequest.getRequestURL().toString().indexOf("squid-internal") == -1) {
            Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.WARNING, "{0} for request {1}\n{2}", new Object[]{failure.getMessage(), clientRequest.getMethod() + " - " + clientRequest.getRequestURL().toString(), ExceptionUtils.getStackTrace(failure)});
        }
    }
    
    @Override
    protected Response.Listener newProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
    {
        return new TunnelProxyResponseListener(request, response);
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
