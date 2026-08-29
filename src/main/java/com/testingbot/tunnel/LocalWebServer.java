package com.testingbot.tunnel;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;

public class LocalWebServer {
    
    public LocalWebServer(String directoryPath) {
        Server server = new Server(8080);

        ResourceHandler resource_handler = new ResourceHandler();

        resource_handler.setDirAllowed(true);
        resource_handler.setWelcomeFiles("index.html", "index.htm");
        resource_handler.setBaseResourceAsString(directoryPath);

        Handler.Sequence handlers = new Handler.Sequence(resource_handler, new DefaultHandler());
        server.setHandler(handlers);
        
        try {
            server.start();
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.INFO, "Local webserver now running on port 8080");
        } catch (Exception ex) {
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
