package com.testingbot.tunnel.proxy;


import com.testingbot.tunnel.App;
import org.eclipse.jetty.servlets.ProxyServlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.IO;

public class ForwarderServlet extends ProxyServlet {
    private App app;
    
    public ForwarderServlet(App app) {
        this.app = app;
    }
    
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }

    @Override
    public void handleConnect(HttpServletRequest request,
                              HttpServletResponse response)
        throws IOException
    {
        String uri = request.getRequestURI();
        
        InetAddrPort addrPort=new InetAddrPort(uri);

        InputStream in=request.getInputStream();
        OutputStream out=response.getOutputStream();

        Socket socket = new Socket(addrPort.getInetAddress(),addrPort.getPort());

        response.setStatus(200);
        response.setHeader("Connection","close");
        response.flushBuffer();

        IO.copyThread(socket.getInputStream(),out);
        IO.copy(in,socket.getOutputStream());
    }
        
    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        
        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)res;
        if ("CONNECT".equalsIgnoreCase(request.getMethod()))
        {
            handleConnect(request,response);
        }
        else
        {
            String uri=request.getRequestURI();
            if (request.getQueryString()!=null)
            {
                uri+="?"+request.getQueryString();
            }
                
            URL url = new URL(request.getScheme(),
                    		  "127.0.0.1",
                    		  4446,
                    		  uri);
            
            URLConnection connection = url.openConnection();
            connection.setAllowUserInteraction(false);
            
            // Set method
            HttpURLConnection http = null;
            if (connection instanceof HttpURLConnection)
            {
                http = (HttpURLConnection)connection;
                http.setRequestMethod(request.getMethod());
                http.setInstanceFollowRedirects(false);
            }

            // check connection header
            String connectionHdr = request.getHeader("Connection");
            if (connectionHdr!=null)
            {
                connectionHdr=connectionHdr.toLowerCase();
                if (connectionHdr.equals("keep-alive")||
                    connectionHdr.equals("close"))
                    connectionHdr=null;
            }
            
            // copy headers
            boolean xForwardedFor=false;
            boolean hasContent=false;
            Enumeration enm = request.getHeaderNames();
            while (enm.hasMoreElements())
            {
                // TODO could be better than this!
                String hdr=(String)enm.nextElement();
                String lhdr=hdr.toLowerCase();

                if (_DontProxyHeaders.contains(lhdr))
                    continue;
                if (connectionHdr!=null && connectionHdr.indexOf(lhdr)>=0)
                    continue;

                if ("content-type".equals(lhdr))
                    hasContent=true;

                Enumeration vals = request.getHeaders(hdr);
                while (vals.hasMoreElements())
                {
                    String val = (String)vals.nextElement();
                    if (val!=null)
                    {
                        connection.addRequestProperty(hdr,val);
                        xForwardedFor|="X-Forwarded-For".equalsIgnoreCase(hdr);
                    }
                }
            }
            
            connection.addRequestProperty("TB-Tunnel", "1");
            connection.addRequestProperty("TB-Credentials", this.app.getClientKey() + "_" + this.app.getClientSecret());
      
            // Proxy headers
            connection.setRequestProperty("Via","1.1 (jetty)");
            if (!xForwardedFor)
                connection.addRequestProperty("X-Forwarded-For",
                                              request.getRemoteAddr());

            // a little bit of cache control
            String cache_control = request.getHeader("Cache-Control");
            if (cache_control!=null &&
                (cache_control.indexOf("no-cache")>=0 ||
                 cache_control.indexOf("no-store")>=0))
                connection.setUseCaches(false);

            // customize Connection
            
            try
            {    
                connection.setDoInput(true);
                
                // do input thang!
                InputStream in=request.getInputStream();
                if (hasContent)
                {
                    connection.setDoOutput(true);
                    IO.copy(in,connection.getOutputStream());
                }
                
                // Connect
                connection.connect();    
            }
            catch (Exception e)
            {
                
            }
            
            InputStream proxy_in = null;

            // handler status codes etc.
            int code=500;
            if (http!=null)
            {
                proxy_in = http.getErrorStream();
                
                code=http.getResponseCode();
                response.setStatus(code,http.getResponseMessage());
            }
            
            if (proxy_in==null)
            {
                try {proxy_in=connection.getInputStream();}
                catch (Exception e)
                {
                    proxy_in = http.getErrorStream();
                }
            }
            
            // clear response defaults.
            response.setHeader("Date",null);
            response.setHeader("Server",null);
            
            // set response headers
            int h=0;
            String hdr=connection.getHeaderFieldKey(h);
            String val=connection.getHeaderField(h);
            while(hdr!=null || val!=null)
            {
                String lhdr = hdr!=null?hdr.toLowerCase():null;
                if (hdr!=null && val!=null && !_DontProxyHeaders.contains(lhdr))
                    response.addHeader(hdr,val);
                
                h++;
                hdr=connection.getHeaderFieldKey(h);
                val=connection.getHeaderField(h);
            }
            response.addHeader("Via","1.1 (jetty)");

            // Handle
            if (proxy_in!=null)
                IO.copy(proxy_in,response.getOutputStream());
            
        }
    } 
}
