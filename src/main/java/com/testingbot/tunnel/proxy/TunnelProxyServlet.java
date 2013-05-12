package com.testingbot.tunnel.proxy;


import org.eclipse.jetty.servlets.ProxyServlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EofException;

public class TunnelProxyServlet extends ProxyServlet {
    
    class CustomHttpExchange extends HttpExchange {
        public long startTime = System.currentTimeMillis();
        
        @Override
        protected void onRequestCommitted() throws IOException
        {
            startTime = System.currentTimeMillis();
        }
    }
    
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }
    
    @Override
    protected void handleOnException(Throwable ex, HttpServletRequest request, HttpServletResponse response)
    {
        if (request.getRequestURL().toString().indexOf("squid-internal") == -1) {
            Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.WARNING, "{0} for request {1}\n{2}", new Object[]{ex.getMessage(), request.getMethod() + " - " + request.getRequestURL().toString(), ExceptionUtils.getStackTrace(ex)});
        }
        
        if (!response.isCommitted())
        {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
       final int debug = _log.isDebugEnabled()?req.hashCode():0;

        final HttpServletRequest request = (HttpServletRequest)req;
        final HttpServletResponse response = (HttpServletResponse)res;

        if ("CONNECT".equalsIgnoreCase(request.getMethod()))
        {
            handleConnect(request,response);
        }
        else
        {
            final InputStream in = request.getInputStream();
            final OutputStream out = response.getOutputStream();

            final Continuation continuation = ContinuationSupport.getContinuation(request);

            if (!continuation.isInitial())
                response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT); // Need better test that isInitial
            else
            {

                String uri = request.getRequestURI();
                if (request.getQueryString() != null)
                    uri += "?" + request.getQueryString();
                if (request.getServerName().equalsIgnoreCase("localhost") && request.getServerPort() == 8087) {
                    throw new ServletException("Bad request on TunnelProxyServlet");
                }
                
                HttpURI url = proxyHttpURI(request,uri);

                if (debug != 0)
                    _log.debug(debug + " proxy " + uri + "-->" + url);

                if (url == null)
                {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }

                CustomHttpExchange exchange = new CustomHttpExchange()
                {
                    @Override
                    protected void onRequestCommitted() throws IOException
                    {
                    }

                    @Override
                    protected void onRequestComplete() throws IOException
                    {
                    }

                    @Override
                    protected void onResponseContent(Buffer content) throws IOException
                    {
                        if (debug != 0)
                            _log.debug(debug + " content" + content.length());
                        content.writeTo(out);
                    }

                    @Override
                    protected void onResponseHeaderComplete() throws IOException
                    {
                    }

                    @Override
                    protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
                    {
                        if (debug != 0)
                            _log.debug(debug + " " + version + " " + status + " " + reason);

                        if (reason != null && reason.length() > 0)
                            response.setStatus(status,reason.toString());
                        else
                            response.setStatus(status);
                    }

                    
                    @Override
                    protected void onResponseComplete() throws IOException
                    {
                        long endTime = System.currentTimeMillis();
                        Logger.getLogger(TunnelProxyServlet.class.getName()).log(Level.INFO, "<< [{0}] {1} ({2}) - {3}", new Object[]{request.getMethod(), request.getRequestURL().toString(), response.toString().substring(9, 12), (endTime-this.startTime) + " ms"});
                        continuation.complete();
                    }
        
                    @Override
                    protected void onResponseHeader(Buffer name, Buffer value) throws IOException
                    {
                        String nameString = name.toString();
                        String s = nameString.toLowerCase(Locale.ENGLISH);
                        if (!_DontProxyHeaders.contains(s) || (HttpHeaders.CONNECTION_BUFFER.equals(name) && HttpHeaderValues.CLOSE_BUFFER.equals(value)))
                        {
                            if (debug != 0)
                                _log.debug(debug + " " + name + ": " + value);

                            String filteredHeaderValue = filterResponseHeaderValue(nameString,value.toString(),request);
                            if (filteredHeaderValue != null && filteredHeaderValue.trim().length() > 0)
                            {
                                if (debug != 0)
                                    _log.debug(debug + " " + name + ": (filtered): " + filteredHeaderValue);
                                response.addHeader(nameString,filteredHeaderValue);
                            }
                        }
                        else if (debug != 0)
                            _log.debug(debug + " " + name + "! " + value);
                    }

                    @Override
                    protected void onConnectionFailed(Throwable ex)
                    {
                        handleOnConnectionFailed(ex,request,response);

                        // it is possible this might trigger before the
                        // continuation.suspend()
                        if (!continuation.isInitial())
                        {
                            continuation.complete();
                        }
                    }

                    @Override
                    protected void onException(Throwable ex)
                    {
                        if (ex instanceof EofException)
                        {
                            _log.ignore(ex);
                            //return;
                        }
                        handleOnException(ex,request,response);

                        // it is possible this might trigger before the
                        // continuation.suspend()
                        if (!continuation.isInitial())
                        {
                            continuation.complete();
                        }
                    }

                    @Override
                    protected void onExpire()
                    {
                        handleOnExpire(request,response);
                        continuation.complete();
                    }

                };

                exchange.setScheme(HttpSchemes.HTTPS.equals(request.getScheme())?HttpSchemes.HTTPS_BUFFER:HttpSchemes.HTTP_BUFFER);
                exchange.setMethod(request.getMethod());
                exchange.setURL(url.toString());
                exchange.setVersion(request.getProtocol());


                if (debug != 0)
                    _log.debug(debug + " " + request.getMethod() + " " + url + " " + request.getProtocol());

                // check connection header
                String connectionHdr = request.getHeader("Connection");
                if (connectionHdr != null)
                {
                    connectionHdr = connectionHdr.toLowerCase(Locale.ENGLISH);
                    if (connectionHdr.indexOf("keep-alive") < 0 && connectionHdr.indexOf("close") < 0)
                        connectionHdr = null;
                }

                // force host
                if (_hostHeader != null)
                    exchange.setRequestHeader("Host",_hostHeader);

                // copy headers
                boolean xForwardedFor = false;
                boolean hasContent = false;
                long contentLength = -1;
                Enumeration<?> enm = request.getHeaderNames();
                while (enm.hasMoreElements())
                {
                    // TODO could be better than this!
                    String hdr = (String)enm.nextElement();
                    String lhdr = hdr.toLowerCase(Locale.ENGLISH);

                    if (_DontProxyHeaders.contains(lhdr))
                        continue;
                    if (connectionHdr != null && connectionHdr.indexOf(lhdr) >= 0)
                        continue;
                    if (_hostHeader != null && "host".equals(lhdr))
                        continue;

                    if ("content-type".equals(lhdr))
                        hasContent = true;
                    else if ("content-length".equals(lhdr))
                    {
                        contentLength = request.getContentLength();
                        exchange.setRequestHeader(HttpHeaders.CONTENT_LENGTH,Long.toString(contentLength));
                        if (contentLength > 0)
                            hasContent = true;
                    }
                    else if ("x-forwarded-for".equals(lhdr))
                        xForwardedFor = true;

                    Enumeration<?> vals = request.getHeaders(hdr);
                    while (vals.hasMoreElements())
                    {
                        String val = (String)vals.nextElement();
                        if (val != null)
                        {
                            if (debug != 0)
                                _log.debug(debug + " " + hdr + ": " + val);

                            exchange.setRequestHeader(hdr,val);
                        }
                    }
                }

                // Proxy headers
                exchange.setRequestHeader("Via","1.1 (jetty)");
                if (!xForwardedFor)
                {
                    exchange.addRequestHeader("X-Forwarded-For",request.getRemoteAddr());
                    exchange.addRequestHeader("X-Forwarded-Proto",request.getScheme());
                    exchange.addRequestHeader("X-Forwarded-Host",request.getHeader("Host"));
                    exchange.addRequestHeader("X-Forwarded-Server",request.getLocalName());
                }

                if (hasContent)
                {
                    exchange.setRequestContentSource(in);
                }

                customizeExchange(exchange, request);

                /*
                 * we need to set the timeout on the continuation to take into
                 * account the timeout of the HttpClient and the HttpExchange
                 */
                long ctimeout = (_client.getTimeout() > exchange.getTimeout()) ? _client.getTimeout() : exchange.getTimeout();

                // continuation fudge factor of 1000, underlying components
                // should fail/expire first from exchange
                if ( ctimeout == 0 )
                {
                    continuation.setTimeout(0);  // ideally never times out
                }
                else
                {
                    continuation.setTimeout(ctimeout + 1000);
                }

                customizeContinuation(continuation);

                continuation.suspend(response);
                _client.send(exchange);

            }
        }        
    } 
}
