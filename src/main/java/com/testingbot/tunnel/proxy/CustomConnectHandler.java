/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.testingbot.tunnel.proxy;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ConnectHandler;

/**
 *
 * @author jochen
 */
public class CustomConnectHandler extends ConnectHandler {
    
    @Override
    public void handleConnect(Request baseRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress) throws ServletException, IOException
    {
        String url = request.getRequestURL().toString();
        
        super.handleConnect(baseRequest, request, response, serverAddress);
    }
    
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String url = request.getRequestURL().toString();
        
        String method = request.getMethod();
        Logger.getLogger(CustomConnectHandler.class.getName()).log(Level.INFO, "[{0}] {1} ({2})", new Object[]{method, url, response.toString().substring(9, 12)});
        
        super.handle(target, baseRequest, request, response);
    }
}
