package com.testingbot.tunnel.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The SOCKS5 handshake (RFC 1928, with the RFC 1929 username/password sub-negotiation) written
 * as a state machine over bytes.
 *
 * <p>It performs no I/O of its own. The caller writes {@link #greeting()}, then repeatedly asks
 * {@link #bytesNeeded()} how much the next step wants, hands exactly that many bytes to
 * {@link #accept}, and writes back whatever comes out. That lets the CONNECT path drive the
 * whole exchange from Jetty's selector rather than parking a thread on it.
 *
 * <p>Each step declares an exact byte count, which is also what keeps the tunnel intact: the
 * proxy may put the first bytes of the tunnelled stream in the same segment as its reply, and a
 * read that took more than the handshake needs would swallow them.
 */
final class Socks5Handshake {

    static final byte VERSION = 0x05;
    static final byte AUTH_NONE = 0x00;
    static final byte AUTH_USERPASS = 0x02;
    static final byte AUTH_UNACCEPTABLE = (byte) 0xFF;
    static final byte CMD_CONNECT = 0x01;
    static final byte ATYP_IPV4 = 0x01;
    static final byte ATYP_DOMAIN = 0x03;
    static final byte ATYP_IPV6 = 0x04;
    static final byte REPLY_SUCCESS = 0x00;

    private enum Step {
        /** The method the proxy selected. */
        CHOICE,
        /** The result of the username/password sub-negotiation. */
        AUTH_REPLY,
        /** Version, reply code, reserved byte and address type. */
        REPLY_HEAD,
        /** The length byte of a domain-name bound address. */
        REPLY_DOMAIN_LENGTH,
        /** The bound address and port, read only to leave the stream at the payload. */
        REPLY_ADDRESS,
        DONE
    }

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean offerAuth;

    private Step step = Step.CHOICE;
    private int needed = 2;

    Socks5Handshake(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.offerAuth = user != null && !user.isEmpty();
    }

    /** The opening greeting: version, method count, methods. */
    ByteBuffer greeting() {
        ByteBuffer greeting = ByteBuffer.allocate(offerAuth ? 4 : 3);
        greeting.put(VERSION);
        if (offerAuth) {
            greeting.put((byte) 2).put(AUTH_NONE).put(AUTH_USERPASS);
        } else {
            greeting.put((byte) 1).put(AUTH_NONE);
        }
        return greeting.flip();
    }

    /** How many bytes {@link #accept} expects next; zero once {@link #isDone()}. */
    int bytesNeeded() {
        return needed;
    }

    boolean isDone() {
        return step == Step.DONE;
    }

    /**
     * Advances one step.
     *
     * @param in exactly {@link #bytesNeeded()} bytes, positioned at the first of them
     * @return the bytes to write next, or null when this step only reads
     * @throws IOException if the proxy refuses the tunnel or answers with something we cannot
     *                     make sense of
     */
    ByteBuffer accept(ByteBuffer in) throws IOException {
        switch (step) {
            case CHOICE: {
                if (in.get() != VERSION) {
                    throw new IOException("Upstream SOCKS proxy replied with a version other than 5");
                }
                byte method = in.get();
                if (method == AUTH_UNACCEPTABLE) {
                    throw new IOException(
                            "Upstream SOCKS proxy rejected all offered authentication methods");
                }
                if (method == AUTH_USERPASS) {
                    if (!offerAuth) {
                        throw new IOException("Upstream SOCKS proxy requires authentication but "
                                + "none was configured (set --proxy-userpwd)");
                    }
                    step = Step.AUTH_REPLY;
                    needed = 2;
                    return authentication();
                }
                if (method != AUTH_NONE) {
                    throw new IOException("Upstream SOCKS proxy selected unsupported "
                            + "authentication method " + method);
                }
                step = Step.REPLY_HEAD;
                needed = 4;
                return connectRequest();
            }
            case AUTH_REPLY: {
                in.get();                                   // sub-negotiation version
                if (in.get() != 0x00) {
                    throw new IOException("Upstream SOCKS proxy rejected the supplied credentials");
                }
                step = Step.REPLY_HEAD;
                needed = 4;
                return connectRequest();
            }
            case REPLY_HEAD: {
                if (in.get() != VERSION) {
                    throw new IOException("Malformed SOCKS5 reply");
                }
                byte reply = in.get();
                if (reply != REPLY_SUCCESS) {
                    throw new IOException(String.format(
                            "Upstream SOCKS proxy refused CONNECT to %s:%d (%s)",
                            host, port, replyMessage(reply)));
                }
                in.get();                                   // reserved
                byte atyp = in.get();
                switch (atyp) {
                    case ATYP_IPV4:
                        step = Step.REPLY_ADDRESS;
                        needed = 4 + 2;
                        return null;
                    case ATYP_IPV6:
                        step = Step.REPLY_ADDRESS;
                        needed = 16 + 2;
                        return null;
                    case ATYP_DOMAIN:
                        step = Step.REPLY_DOMAIN_LENGTH;
                        needed = 1;
                        return null;
                    default:
                        throw new IOException(
                                "Unsupported address type in SOCKS5 reply: " + atyp);
                }
            }
            case REPLY_DOMAIN_LENGTH: {
                step = Step.REPLY_ADDRESS;
                needed = (in.get() & 0xFF) + 2;
                return null;
            }
            case REPLY_ADDRESS: {
                // Read and discarded: it exists only so the stream is left at the payload.
                step = Step.DONE;
                needed = 0;
                return null;
            }
            default:
                throw new IOException("SOCKS5 handshake received bytes after it had finished");
        }
    }

    private ByteBuffer authentication() throws IOException {
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] p = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        if (u.length > 255 || p.length > 255) {
            throw new IOException("SOCKS5 username or password exceeds 255 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(3 + u.length + p.length);
        buf.put((byte) 0x01);                               // sub-negotiation version
        buf.put((byte) u.length).put(u);
        buf.put((byte) p.length).put(p);
        return buf.flip();
    }

    /** Addressed by name, so the proxy resolves it rather than this end. */
    private ByteBuffer connectRequest() throws IOException {
        byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        if (hostBytes.length > 255) {
            throw new IOException("Host name too long for SOCKS5: " + host);
        }
        ByteBuffer request = ByteBuffer.allocate(7 + hostBytes.length);
        request.put(VERSION).put(CMD_CONNECT).put((byte) 0x00).put(ATYP_DOMAIN);
        request.put((byte) hostBytes.length).put(hostBytes);
        request.putShort((short) port);
        return request.flip();
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
}
