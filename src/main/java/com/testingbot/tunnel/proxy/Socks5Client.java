package com.testingbot.tunnel.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Minimal SOCKS5 client (RFC 1928 / RFC 1929) for establishing a tunnel through an
 * upstream SOCKS proxy, used by the CONNECT path.
 *
 * <p>Operates on a connected channel in blocking mode: the handshake is a handful of small
 * round trips, and doing it inline is far simpler than threading it through a selector.
 * The caller switches the channel back to non-blocking before handing it to Jetty.
 */
final class Socks5Client {

    static final byte VERSION = 0x05;
    static final byte AUTH_NONE = 0x00;
    static final byte AUTH_USERPASS = 0x02;
    static final byte AUTH_UNACCEPTABLE = (byte) 0xFF;
    static final byte CMD_CONNECT = 0x01;
    static final byte ATYP_DOMAIN = 0x03;
    static final byte ATYP_IPV4 = 0x01;
    static final byte ATYP_IPV6 = 0x04;
    static final byte REPLY_SUCCESS = 0x00;

    private Socks5Client() {
    }

    /**
     * Performs the SOCKS5 greeting, optional username/password sub-negotiation, and CONNECT
     * request. Returns normally once the tunnel to {@code host:port} is open.
     *
     * @throws IOException if the proxy rejects the handshake or the connection fails
     */
    static void connect(SocketChannel channel, String host, int port,
                        String user, String password) throws IOException {
        boolean offerAuth = user != null && !user.isEmpty();

        // Greeting: version, number of methods, methods.
        ByteBuffer greeting = ByteBuffer.allocate(offerAuth ? 4 : 3);
        greeting.put(VERSION);
        if (offerAuth) {
            greeting.put((byte) 2).put(AUTH_NONE).put(AUTH_USERPASS);
        } else {
            greeting.put((byte) 1).put(AUTH_NONE);
        }
        greeting.flip();
        writeFully(channel, greeting);

        ByteBuffer choice = readFully(channel, 2);
        if (choice.get(0) != VERSION) {
            throw new IOException("Upstream SOCKS proxy replied with version " + choice.get(0) + ", expected 5");
        }
        byte method = choice.get(1);
        if (method == AUTH_UNACCEPTABLE) {
            throw new IOException("Upstream SOCKS proxy rejected all offered authentication methods");
        }
        if (method == AUTH_USERPASS) {
            if (!offerAuth) {
                throw new IOException("Upstream SOCKS proxy requires authentication but none was configured "
                        + "(set --proxy-userpwd)");
            }
            authenticate(channel, user, password);
        } else if (method != AUTH_NONE) {
            throw new IOException("Upstream SOCKS proxy selected unsupported authentication method " + method);
        }

        // CONNECT request, addressed by domain name so the proxy resolves it.
        byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        if (hostBytes.length > 255) {
            throw new IOException("Host name too long for SOCKS5: " + host);
        }
        ByteBuffer request = ByteBuffer.allocate(7 + hostBytes.length);
        request.put(VERSION).put(CMD_CONNECT).put((byte) 0x00).put(ATYP_DOMAIN);
        request.put((byte) hostBytes.length).put(hostBytes);
        request.putShort((short) port);
        request.flip();
        writeFully(channel, request);

        readConnectReply(channel, host, port);
    }

    private static void authenticate(SocketChannel channel, String user, String password) throws IOException {
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] p = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        if (u.length > 255 || p.length > 255) {
            throw new IOException("SOCKS5 username or password exceeds 255 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(3 + u.length + p.length);
        buf.put((byte) 0x01);                       // sub-negotiation version
        buf.put((byte) u.length).put(u);
        buf.put((byte) p.length).put(p);
        buf.flip();
        writeFully(channel, buf);

        ByteBuffer reply = readFully(channel, 2);
        if (reply.get(1) != 0x00) {
            throw new IOException("Upstream SOCKS proxy rejected the supplied credentials");
        }
    }

    private static void readConnectReply(SocketChannel channel, String host, int port) throws IOException {
        ByteBuffer head = readFully(channel, 4);
        if (head.get(0) != VERSION) {
            throw new IOException("Malformed SOCKS5 reply");
        }
        byte reply = head.get(1);
        if (reply != REPLY_SUCCESS) {
            throw new IOException(String.format(
                    "Upstream SOCKS proxy refused CONNECT to %s:%d (%s)", host, port, replyMessage(reply)));
        }
        // Drain the bound address so the stream is positioned at the tunnelled payload.
        byte atyp = head.get(3);
        switch (atyp) {
            case ATYP_IPV4:
                readFully(channel, 4 + 2);
                break;
            case ATYP_IPV6:
                readFully(channel, 16 + 2);
                break;
            case ATYP_DOMAIN:
                int len = readFully(channel, 1).get(0) & 0xFF;
                readFully(channel, len + 2);
                break;
            default:
                throw new IOException("Unsupported address type in SOCKS5 reply: " + atyp);
        }
    }

    static String replyMessage(byte reply) {
        switch (reply) {
            case 0x01: return "general SOCKS server failure";
            case 0x02: return "connection not allowed by ruleset";
            case 0x03: return "network unreachable";
            case 0x04: return "host unreachable";
            case 0x05: return "connection refused";
            case 0x06: return "TTL expired";
            case 0x07: return "command not supported";
            case 0x08: return "address type not supported";
            default: return "reply code " + reply;
        }
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        // Via the socket adaptor so SO_TIMEOUT applies; SocketChannel ignores it.
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        channel.socket().getOutputStream().write(bytes);
        channel.socket().getOutputStream().flush();
    }

    /**
     * Reads exactly {@code length} bytes, subject to the socket's SO_TIMEOUT.
     *
     * <p>Reading via {@link SocketChannel#read} instead would ignore SO_TIMEOUT, so a SOCKS
     * proxy that accepted the connection and then stalled would hang this thread forever --
     * and with it the CONNECT request whose callback is never completed.
     */
    private static ByteBuffer readFully(SocketChannel channel, int length) throws IOException {
        byte[] bytes = new byte[length];
        int off = 0;
        java.io.InputStream in = channel.socket().getInputStream();
        while (off < length) {
            int n = in.read(bytes, off, length - off);
            if (n < 0) {
                throw new IOException("Upstream SOCKS proxy closed the connection during handshake");
            }
            off += n;
        }
        return ByteBuffer.wrap(bytes);
    }
}
