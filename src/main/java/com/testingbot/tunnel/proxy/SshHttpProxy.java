package com.testingbot.tunnel.proxy;

import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Takes the SSH connection through an HTTP proxy with {@code CONNECT}.
 *
 * <p>JSch ships {@code ProxyHTTP}, but it can only do Basic. Since the whole point of TB-321 is
 * networks where the proxy demands Negotiate, the CONNECT is done here instead so it can use the
 * same {@link ProxyAuthenticator} as the other two egress paths.
 *
 * <p>Note this is also the first time the SSH connection traverses {@code --proxy} at all.
 * Previously {@code --proxy} affected browser traffic only, so on a network whose only egress is
 * a proxy the tunnel could not establish its SSH connection in the first place -- the failure
 * came before any question of which authentication scheme to use.
 */
public class SshHttpProxy implements Proxy {

    private static final Logger LOG = Logger.getLogger(SshHttpProxy.class.getName());

    /** Enough for a status line and headers; a proxy sending more than this is misbehaving. */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final String proxyHost;
    private final int proxyPort;
    private final ProxyAuthenticator authenticator;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public SshHttpProxy(String proxyHost, int proxyPort, ProxyAuthenticator authenticator) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.authenticator = authenticator == null ? ProxyAuthenticator.none() : authenticator;
    }

    @Override
    public void connect(SocketFactory socketFactory, String host, int port, int timeout)
            throws IOException {
        try {
            socket = socketFactory == null
                    ? new Socket(proxyHost, proxyPort)
                    : socketFactory.createSocket(proxyHost, proxyPort);
            socket.setTcpNoDelay(true);
            if (timeout > 0) {
                socket.setSoTimeout(timeout);
            }
            in = socketFactory == null ? socket.getInputStream() : socketFactory.getInputStream(socket);
            out = socketFactory == null ? socket.getOutputStream() : socketFactory.getOutputStream(socket);

            String status;
            try {
                out.write(connectRequest(host, port).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                status = readStatusLine();
            } catch (IOException handshakeFailed) {
                // Name the proxy whatever went wrong: a bare "Connection reset" here reads as a
                // problem with the TestingBot endpoint rather than with the hop in between.
                throw new IOException("CONNECT to " + host + ":" + port + " via upstream proxy "
                        + proxyHost + ":" + proxyPort + " failed: " + handshakeFailed.getMessage(),
                        handshakeFailed);
            }
            if (!isSuccess(status)) {
                throw new IOException("Upstream proxy " + proxyHost + ":" + proxyPort
                        + " refused CONNECT to " + host + ":" + port + ": " + status
                        + (status.contains("407")
                            ? ". Check --proxy-auth-scheme, and run --doctor if using negotiate."
                            : ""));
            }
            if (timeout > 0) {
                // The handshake is done; SSH itself must not inherit a read deadline.
                socket.setSoTimeout(0);
            }
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
    }

    String connectRequest(String host, int port) {
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n");
        String authorization = authenticator.authorizationValue(proxyHost);
        if (authorization != null) {
            request.append("Proxy-Authorization: ").append(authorization).append("\r\n");
        }
        request.append("Proxy-Connection: keep-alive\r\n").append("\r\n");
        return request.toString();
    }

    /**
     * Reads the status line and drains the remaining headers, stopping exactly at the end of
     * the header block so the SSH banner that follows is left untouched.
     */
    private String readStatusLine() throws IOException {
        StringBuilder response = new StringBuilder();
        int consecutiveNewlines = 0;
        while (consecutiveNewlines < 2) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Upstream proxy " + proxyHost + ":" + proxyPort
                        + " closed the connection during CONNECT");
            }
            if (b == '\n') {
                consecutiveNewlines++;
            } else if (b != '\r') {
                consecutiveNewlines = 0;
            }
            response.append((char) b);
            if (response.length() > MAX_RESPONSE_BYTES) {
                throw new IOException("Upstream proxy sent more than " + MAX_RESPONSE_BYTES
                        + " bytes of CONNECT response headers");
            }
        }
        String text = response.toString();
        int eol = text.indexOf('\n');
        return (eol < 0 ? text : text.substring(0, eol)).trim();
    }

    static boolean isSuccess(String statusLine) {
        // "HTTP/1.1 200 Connection established"
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) {
            return false;
        }
        try {
            int status = Integer.parseInt(parts[1]);
            return status >= 200 && status < 300;
        } catch (NumberFormatException notAStatus) {
            return false;
        }
    }

    @Override
    public InputStream getInputStream() {
        return in;
    }

    @Override
    public OutputStream getOutputStream() {
        return out;
    }

    @Override
    public Socket getSocket() {
        return socket;
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Ignored error closing the proxy socket", ex);
        } finally {
            socket = null;
            in = null;
            out = null;
        }
    }
}
