package com.testingbot.tunnel;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;

public class LocalWebServer {

    static final int PORT = 8080;

    public LocalWebServer(String directoryPath, String bindAddress) {
        Server server = new Server();
        // new Server(port) binds the wildcard address, which published an operator-chosen
        // directory -- with listing enabled, below -- to every host that could route here.
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(bindAddress);
        connector.setPort(PORT);
        server.addConnector(connector);

        ResourceHandler resource_handler = new ResourceHandler();

        resource_handler.setDirAllowed(true);
        resource_handler.setWelcomeFiles("index.html", "index.htm");
        resource_handler.setBaseResourceAsString(directoryPath);

        Handler.Sequence handlers = new Handler.Sequence(resource_handler, new DefaultHandler());
        server.setHandler(handlers);
        
        try {
            server.start();
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.INFO, "Local webserver now running on {0}:{1}", new Object[]{bindAddress, PORT});
        } catch (Exception ex) {
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
