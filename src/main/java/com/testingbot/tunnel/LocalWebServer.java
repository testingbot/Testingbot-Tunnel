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

    /**
     * Held, not constructor-local.
     *
     * <p>It was local, so nothing could ever stop this server: it served an operator-chosen
     * directory, with listing enabled, for the life of the JVM, and outlived the tunnel it was
     * started alongside. For an embedder that also meant a leaked Jetty server and a held port
     * per App, and it left port 8080 bound so the next run could not start one.
     */
    private final Server server;

    public LocalWebServer(String directoryPath, String bindAddress) {
        this(directoryPath, bindAddress, PORT);
    }

    /** @param port for tests, which cannot assume 8080 is free */
    LocalWebServer(String directoryPath, String bindAddress, int port) {
        server = new Server();
        // new Server(port) binds the wildcard address, which published an operator-chosen
        // directory -- with listing enabled, below -- to every host that could route here.
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(bindAddress);
        connector.setPort(port);
        server.addConnector(connector);

        ResourceHandler resource_handler = new ResourceHandler();

        resource_handler.setDirAllowed(true);
        resource_handler.setWelcomeFiles("index.html", "index.htm");
        resource_handler.setBaseResourceAsString(directoryPath);

        Handler.Sequence handlers = new Handler.Sequence(resource_handler, new DefaultHandler());
        server.setHandler(handlers);
        
        try {
            server.start();
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.INFO, "Local webserver now running on {0}:{1}", new Object[]{bindAddress, port});
        } catch (Exception ex) {
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /** The port actually bound, which differs from {@link #PORT} only in tests. */
    int getPort() {
        return server.getConnectors().length == 0 ? -1
                : ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    boolean isRunning() {
        return server.isRunning();
    }

    public void stop() {
        try {
            server.stop();
        } catch (Exception ex) {
            Logger.getLogger(LocalWebServer.class.getName()).log(Level.WARNING,
                    "Could not stop the local webserver", ex);
        }
    }
}
