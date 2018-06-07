package com.testingbot.tunnel.proxy;

import java.net.URI;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.util.Attributes;

public class CustomAuthentication extends BasicAuthentication {
    
    public CustomAuthentication(URI uri, String realm, String user, String password) {
        super(uri, realm, user, password);
    }
    
    @Override
    public boolean matches(String type, URI uri, String realm) {
        return true;
    }
    
    @Override
    public Result authenticate(Request request, ContentResponse response, HeaderInfo headerInfo, Attributes context) {
        return super.authenticate(request, response, headerInfo, context);
    }
}
