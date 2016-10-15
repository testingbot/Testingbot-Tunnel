package com.testingbot.tunnel.proxy;

import java.io.IOException;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ConnectHandler;

/**
 *
 * @author TestingBot
 */
public class CustomConnectHandler extends ConnectHandler {
    private boolean debugMode = false;
    
    public void setDebugMode(boolean mode) {
        debugMode = mode;
    }
    
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String method = request.getMethod();
        if (method.equalsIgnoreCase("CONNECT")) {
            Logger.getLogger(CustomConnectHandler.class.getName()).log(Level.INFO, "<< [{0}] {1} ({2})", new Object[]{method, request.getRequestURL().toString(), response.toString().substring(9, 12)});
        }
        
         if (debugMode == true) {
            Enumeration<String> headerNames = request.getHeaderNames();
            if (headerNames != null) {
                StringBuilder sb = new StringBuilder();
                String header;

                while (headerNames.hasMoreElements()) {
                    header = headerNames.nextElement();
                    sb.append(header).append(": ").append(request.getHeader(header)).append(System.getProperty("line.separator"));
                }
                Logger.getLogger(CustomConnectHandler.class.getName()).log(Level.INFO, sb.toString());
            }
        }
        
        super.handle(target, baseRequest, request, response);
    }
}
