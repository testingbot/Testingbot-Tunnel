package com.testingbot.tunnel.proxy;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.Statistics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Promise;

/**
 *
 * @author TestingBot
 */
public class CustomConnectHandler extends ConnectHandler {
    private boolean debugMode = false;

    private final String proxyHost;
    private final int proxyPort;
    private final String proxyAuth;

    public CustomConnectHandler(final App app) {
      final String proxy = app.getProxy();
      if (proxy != null) {
        final int colon = proxy.indexOf(':');
        if (colon != -1) {
          proxyHost = proxy.substring(0, colon);
          proxyPort = Integer.parseInt(proxy.substring(colon + 1));
        }
        else {
          proxyHost = proxy;
          proxyPort = 80;
        }
      }
      else {
        proxyHost = null;
        proxyPort = -1;
      }

      proxyAuth = Base64.getEncoder().encodeToString(app.getProxyAuth().getBytes());
    }

    public void setDebugMode(boolean mode) {
        debugMode = mode;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String method = request.getMethod();
        Statistics.addRequest();

        if (HttpMethod.CONNECT.is(request.getMethod())) {
            Logger.getLogger(App.class.getName()).log(Level.INFO, "<< [{0}] {1} ({2})", new Object[]{method, request.getRequestURL().toString(), response.toString().substring(9, 12)});
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
                Logger.getLogger(App.class.getName()).log(Level.INFO, sb.toString());
            }
        }

        super.handle(target, baseRequest, request, response);
    }

    @Override
    protected void connectToServer(HttpServletRequest request, String host, int port, Promise<SocketChannel> promise) {
        if (proxyHost == null)
            super.connectToServer(request, host, port, promise);
        else
            connectToProxy(request, promise);
    }

    private void connectToProxy(HttpServletRequest request, Promise<SocketChannel> promise) {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            channel.connect(newConnectAddress(proxyHost, proxyPort));

            final Selector selector = Selector.open();
            channel.register(selector, SelectionKey.OP_CONNECT);
            while (selector.select() > 0) {
                final Set<SelectionKey> keys = selector.selectedKeys();
                final Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    final SelectionKey key = iterator.next();
                    iterator.remove();

                    if (key.isConnectable()) {
                        if (!channel.finishConnect())
                            throw new IOException("Failure: channel.finishConnect()");

                        channel.register(selector, SelectionKey.OP_READ);

                        final StringBuilder connect = new StringBuilder();
                        connect.append(request.getMethod()).append(' ').append(request.getPathInfo()).append(' ').append(request.getProtocol());
                        final Enumeration<String> headerNames = request.getHeaderNames();
                        while (headerNames.hasMoreElements()) {
                            final String headerName = headerNames.nextElement();
                            final String headerValue = request.getHeader(headerName);
                            connect.append('\n').append(headerName).append(": ").append(headerValue);
                        }

                        if (proxyAuth != null)
                            connect.append("\nProxy-Authorization: Basic ").append(proxyAuth);

                        connect.append("\r\n\r\n");

                        final ByteBuffer buffer = ByteBuffer.wrap(connect.toString().getBytes());
                        while (buffer.hasRemaining())
                            channel.write(buffer);
                    }
                    else if (key.isReadable() && channel.isConnected()) {
                        final ByteBuffer buffer = ByteBuffer.allocate(1024);
                        final StringBuilder response = new StringBuilder();
                        while (channel.read(buffer) > 0) {
                            buffer.flip();
                            while (buffer.hasRemaining())
                                response.append((char)buffer.get());
                        }

                        if (!response.toString().contains("200"))
                            throw new IOException("Channel error response:\n" + response);

                        try {
                            selector.close();
                        }
                        catch (final IOException e) {
                            LOG.ignore(e);
                        }

                        promise.succeeded(channel);
                        return;
                    }
                }
            }
        }
        catch (IOException x) {
            if (channel != null) {
                try {
                    channel.close();
                }
                catch (IOException t) {
                    LOG.ignore(t);
                }
            }
            promise.failed(x);
        }
    }
}