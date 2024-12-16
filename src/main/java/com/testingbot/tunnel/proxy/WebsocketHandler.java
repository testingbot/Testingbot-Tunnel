package com.testingbot.tunnel.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.proxy.ProxyConnection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketHandler extends HandlerWrapper {
    protected static final Logger LOG = LoggerFactory.getLogger(WebsocketHandler.class);
    private final Set<String> whiteList;
    private final Set<String> blackList;
    private Executor executor;
    private Scheduler scheduler;
    private ByteBufferPool bufferPool;
    private SelectorManager selector;
    private long connectTimeout;
    private long idleTimeout;
    private int bufferSize;

    public WebsocketHandler() {
        this((Handler)null);
    }

    public WebsocketHandler(Handler handler) {
        this.whiteList = new HashSet();
        this.blackList = new HashSet();
        this.connectTimeout = 15000L;
        this.idleTimeout = 30000L;
        this.bufferSize = 4096;
        this.setHandler(handler);
    }

    public Executor getExecutor() {
        return this.executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public Scheduler getScheduler() {
        return this.scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.updateBean(this.scheduler, scheduler);
        this.scheduler = scheduler;
    }

    public ByteBufferPool getByteBufferPool() {
        return this.bufferPool;
    }

    public void setByteBufferPool(ByteBufferPool bufferPool) {
        this.updateBean(this.bufferPool, bufferPool);
        this.bufferPool = bufferPool;
    }

    public long getConnectTimeout() {
        return this.connectTimeout;
    }

    public void setConnectTimeout(long connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public long getIdleTimeout() {
        return this.idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    private void forwardWebSocketUpgradeHeaders(HttpServletRequest clientRequest, EndPoint downstreamEndPoint) throws IOException {
        // Get the SocketChannel from the EndPoint
        SocketChannel socketChannel = ((SocketChannelEndPoint) downstreamEndPoint).getChannel();

        // Create the WebSocket upgrade headers
        StringBuilder requestHeaders = new StringBuilder();
        String method = clientRequest.getMethod();
        String uri = clientRequest.getRequestURI();
        String protocol = clientRequest.getProtocol();

        // Add the method, URI, and protocol to the request
        requestHeaders.append(method).append(" ").append(uri).append(" ").append(protocol).append("\r\n");

        // Forward the headers from the HttpServletRequest to the WebSocket server
        for (String headerName : Collections.list(clientRequest.getHeaderNames())) {
            String headerValue = clientRequest.getHeader(headerName);
            requestHeaders.append(headerName).append(": ").append(headerValue).append("\r\n");
        }
        requestHeaders.append("\r\n");  // End of headers

        // Write the headers to the SocketChannel
        ByteBuffer buffer = ByteBuffer.wrap(requestHeaders.toString().getBytes());
        socketChannel.write(buffer);

        // Ensure headers are sent
        socketChannel.socket().getOutputStream().flush();
    }

    public int getBufferSize() {
        return this.bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    protected void doStart() throws Exception {
        if (this.executor == null) {
            this.executor = this.getServer().getThreadPool();
        }

        if (this.scheduler == null) {
            this.scheduler = (Scheduler)this.getServer().getBean(Scheduler.class);
            if (this.scheduler == null) {
                this.scheduler = new ScheduledExecutorScheduler(String.format("Proxy-Scheduler-%x", this.hashCode()), false);
            }

            this.addBean(this.scheduler);
        }

        if (this.bufferPool == null) {
            this.bufferPool = new MappedByteBufferPool();
            this.addBean(this.bufferPool);
        }

        this.addBean(this.selector = this.newSelectorManager());
        this.selector.setConnectTimeout(this.getConnectTimeout());
        super.doStart();
    }

    protected SelectorManager newSelectorManager() {
        return new ConnectManager(this.getExecutor(), this.getScheduler(), 1);
    }

    public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String serverAddress = jettyRequest.getHttpURI().getAuthority();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            StringBuilder sb = new StringBuilder();
            String header;

            while (headerNames.hasMoreElements()) {
                header = headerNames.nextElement();
                sb.append(header).append(": ").append(request.getHeader(header)).append(System.lineSeparator());
            }
        }

        if (request.getHeader("Upgrade") != null && request.getHeader("Upgrade").equalsIgnoreCase("websocket")) {
            this.handleConnect(jettyRequest, request, response, serverAddress);
        } else {
            super.handle(target, jettyRequest, request, response);
        }
    }

    protected void handleConnect(Request baseRequest, final HttpServletRequest request, final HttpServletResponse response, String serverAddress) {
        baseRequest.setHandled(true);

        try {
            HostPort hostPort = new HostPort(serverAddress);
            String host = hostPort.getHost();
            int port = hostPort.getPort(80);

            final HttpChannel httpChannel = baseRequest.getHttpChannel();
            if (!httpChannel.isTunnellingSupported()) {
                    LOG.info("WS not supported for {}", httpChannel);

                this.sendConnectResponse(request, response, 403);
                return;
            }

            final AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(0L);
                LOG.info("Connecting to {}:{}", host, port);

            this.connectToServer(request, host, port, new Promise<SocketChannel>() {
                public void succeeded(SocketChannel channel) {

                    ConnectContext connectContext = new ConnectContext(request, response, asyncContext, httpChannel.getTunnellingEndPoint());
                    if (channel.isConnected()) {
                        WebsocketHandler.this.selector.accept(channel, connectContext);
                    } else {
                        WebsocketHandler.this.selector.connect(channel, connectContext);
                    }

                }

                public void failed(Throwable x) {
                    WebsocketHandler.this.onConnectFailure(request, response, asyncContext, x);
                }
            });
        } catch (Exception x) {
            this.onConnectFailure(request, response, (AsyncContext)null, x);
        }

    }

    protected void connectToServer(HttpServletRequest request, String host, int port, Promise<SocketChannel> promise) {
        SocketChannel channel = null;

        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            InetSocketAddress address = this.newConnectAddress(host, port);
            channel.connect(address);
            promise.succeeded(channel);
        } catch (Throwable x) {
            this.close(channel);
            promise.failed(x);
        }

    }

    private void close(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable x) {
            LOG.trace("IGNORED", x);
        }

    }

    protected InetSocketAddress newConnectAddress(String host, int port) {
        return new InetSocketAddress(host, port);
    }

    protected void onConnectSuccess(ConnectContext connectContext,WebsocketHandler.UpstreamConnection upstreamConnection) {
        ConcurrentMap<String, Object> context = connectContext.getContext();
        HttpServletRequest request = connectContext.getRequest();
        this.prepareContext(request, context);
        EndPoint downstreamEndPoint = connectContext.getEndPoint();
        DownstreamConnection downstreamConnection = this.newDownstreamConnection(downstreamEndPoint, context);
        downstreamConnection.setInputBufferSize(this.getBufferSize());
        upstreamConnection.setConnection(downstreamConnection);
        downstreamConnection.setConnection(upstreamConnection);
            LOG.info("Connection setup completed: {}<->{}", downstreamConnection, upstreamConnection);


        // Send the WebSocket upgrade response
        HttpServletResponse response = connectContext.getResponse();

        // Forward the WebSocket upgrade headers to the downstream connection
        try {
            forwardWebSocketUpgradeHeaders(request, upstreamConnection.getEndPoint());
        } catch (IOException e) {
            LOG.error("Error while forwarding WebSocket upgrade headers", e);
            onConnectFailure(request, response, connectContext.getAsyncContext(), e);
        }

        this.sendConnectResponse(request, response, 101);
        this.upgradeConnection(request, response, downstreamConnection);
        connectContext.getAsyncContext().complete();
    }

    protected void onConnectFailure(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, Throwable failure) {
            LOG.info("CONNECT failed", failure);

        this.sendConnectResponse(request, response, 500);
        if (asyncContext != null) {
            asyncContext.complete();
        }

    }

    private void sendConnectResponse(HttpServletRequest request, HttpServletResponse response, int statusCode) {
        try {
            response.setStatus(statusCode);
                LOG.info("CONNECT response sent {} {}", request.getProtocol(), statusCode);
        } catch (Throwable x) {
                LOG.info("Could not send CONNECT response", x);
        }

    }

    protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) {
        return true;
    }

    protected DownstreamConnection newDownstreamConnection(EndPoint endPoint, ConcurrentMap<String, Object> context) {
        return new DownstreamConnection(endPoint, this.getExecutor(), this.getByteBufferPool(), context);
    }

    protected UpstreamConnection newUpstreamConnection(EndPoint endPoint, ConnectContext connectContext) {
        return new UpstreamConnection(endPoint, this.getExecutor(), this.getByteBufferPool(), connectContext);
    }

    protected void prepareContext(HttpServletRequest request, ConcurrentMap<String, Object> context) {
    }

    private void upgradeConnection(HttpServletRequest request, HttpServletResponse response, Connection connection) {
        request.setAttribute(HttpTransport.UPGRADE_CONNECTION_ATTRIBUTE, connection);
            LOG.info("Upgraded connection to {}", connection);

    }

    protected int read(EndPoint endPoint, ByteBuffer buffer, ConcurrentMap<String, Object> context) throws IOException {
        int read = endPoint.fill(buffer);
            LOG.info("{} read {} bytes", this, read);

        return read;
    }

    protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback, ConcurrentMap<String, Object> context) {
            LOG.info("{} writing {} bytes", this, buffer.remaining());

        endPoint.write(callback, new ByteBuffer[]{buffer});
    }

    public Set<String> getWhiteListHosts() {
        return this.whiteList;
    }

    public Set<String> getBlackListHosts() {
        return this.blackList;
    }

    public boolean validateDestination(String host, int port) {
        String hostPort = host + ":" + port;
        if (!this.whiteList.isEmpty() && !this.whiteList.contains(hostPort)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Host {}:{} not whitelisted", host, port);
            }

            return false;
        } else if (!this.blackList.isEmpty() && this.blackList.contains(hostPort)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Host {}:{} blacklisted", host, port);
            }

            return false;
        } else {
            return true;
        }
    }

    protected class ConnectManager extends SelectorManager {
        protected ConnectManager(Executor executor, Scheduler scheduler, int selectors) {
            super(executor, scheduler, selectors);
        }

        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key) {
            SocketChannelEndPoint endPoint = new SocketChannelEndPoint((SocketChannel)channel, selector, key, this.getScheduler());
            endPoint.setIdleTimeout(WebsocketHandler.this.getIdleTimeout());
            return endPoint;
        }

        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException {
                WebsocketHandler.LOG.info("Connected to {}", ((SocketChannel)channel).getRemoteAddress());

            WebsocketHandler.ConnectContext connectContext = (WebsocketHandler.ConnectContext)attachment;
            WebsocketHandler.UpstreamConnection connection = WebsocketHandler.this.newUpstreamConnection(endpoint, connectContext);
            connection.setInputBufferSize(WebsocketHandler.this.getBufferSize());
            return connection;
        }

        protected void connectionFailed(SelectableChannel channel, Throwable ex, Object attachment) {
            WebsocketHandler.this.close(channel);
            WebsocketHandler.ConnectContext connectContext = (WebsocketHandler.ConnectContext)attachment;
            WebsocketHandler.this.onConnectFailure(connectContext.request, connectContext.response, connectContext.asyncContext, ex);
        }
    }

    protected static class ConnectContext {
        private final ConcurrentMap<String, Object> context = new ConcurrentHashMap();
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext asyncContext;
        private final EndPoint endPoint;

        public ConnectContext(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, EndPoint endPoint) {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
            this.endPoint = endPoint;
        }

        public ConcurrentMap<String, Object> getContext() {
            return this.context;
        }

        public HttpServletRequest getRequest() {
            return this.request;
        }

        public HttpServletResponse getResponse() {
            return this.response;
        }

        public AsyncContext getAsyncContext() {
            return this.asyncContext;
        }

        public EndPoint getEndPoint() {
            return this.endPoint;
        }
    }

    public class UpstreamConnection extends ProxyConnection implements AsyncListener {
        private final WebsocketHandler.ConnectContext connectContext;

        public UpstreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, WebsocketHandler.ConnectContext connectContext) {
            super(endPoint, executor, bufferPool, connectContext.getContext());
            this.connectContext = connectContext;
        }

        public void onOpen() {
            super.onOpen();
            this.connectContext.asyncContext.addListener(this);
            WebsocketHandler.this.onConnectSuccess(this.connectContext, this);
        }

        protected int read(EndPoint endPoint, ByteBuffer buffer) throws IOException {
            return WebsocketHandler.this.read(endPoint, buffer, this.getContext());
        }

        protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback) {
            WebsocketHandler.this.write(endPoint, buffer, callback, this.getContext());
        }

        public void onComplete(AsyncEvent event) {
            this.fillInterested();
        }

        public void onTimeout(AsyncEvent event) {
        }

        public void onError(AsyncEvent event) {
            this.close(event.getThrowable());
        }

        public void onStartAsync(AsyncEvent event) {
        }
    }

    public class DownstreamConnection extends ProxyConnection implements Connection.UpgradeTo {
        private ByteBuffer buffer;

        public DownstreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ConcurrentMap<String, Object> context) {
            super(endPoint, executor, bufferPool, context);
        }

        public void onUpgradeTo(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        public void onOpen() {
            super.onOpen();
            if (this.buffer == null) {
                this.fillInterested();
            } else {
                final int remaining = this.buffer.remaining();
                this.write(this.getConnection().getEndPoint(), this.buffer, new Callback() {
                    public void succeeded() {
                        WebsocketHandler.DownstreamConnection.this.buffer = null;
                            ProxyConnection.LOG.info("{} wrote initial {} bytes to server", WebsocketHandler.DownstreamConnection.this, remaining);

                        WebsocketHandler.DownstreamConnection.this.fillInterested();
                    }

                    public void failed(Throwable x) {
                        WebsocketHandler.DownstreamConnection.this.buffer = null;
                            ProxyConnection.LOG.info("{} failed to write initial {} bytes to server", new Object[]{this, remaining, x});

                        WebsocketHandler.DownstreamConnection.this.close();
                        WebsocketHandler.DownstreamConnection.this.getConnection().close();
                    }
                });
            }
        }

        protected int read(EndPoint endPoint, ByteBuffer buffer) throws IOException {
            return WebsocketHandler.this.read(endPoint, buffer, this.getContext());
        }

        protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback) {
            WebsocketHandler.this.write(endPoint, buffer, callback, this.getContext());
        }
    }
}
