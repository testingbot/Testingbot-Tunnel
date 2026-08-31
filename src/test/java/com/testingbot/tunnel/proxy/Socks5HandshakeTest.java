package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives Socks5Handshake against a hand-rolled SOCKS5 server so the wire format is checked
 * byte for byte, including the RFC 1929 username/password sub-negotiation.
 *
 * <p>The handshake does no I/O of its own -- in production Jetty's selector feeds it. {@link
 * #pump} is the blocking equivalent, so these tests exercise the same state machine over a
 * real socket without a selector in the way.
 */
class Socks5HandshakeTest {

    private ServerSocket server;
    private Thread serverThread;

    @AfterEach
    void tearDown() throws Exception {
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(2_000);
        }
        if (server != null && !server.isClosed()) {
            server.close();
        }
    }

    /** Records what the client sent and replies with the scripted bytes. */
    private AtomicReference<String> runServer(byte methodChoice, Byte authStatus, byte connectReply)
            throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        AtomicReference<String> observed = new AtomicReference<>();

        serverThread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                StringBuilder log = new StringBuilder();

                // Greeting
                int version = in.readUnsignedByte();
                int nMethods = in.readUnsignedByte();
                byte[] methods = new byte[nMethods];
                in.readFully(methods);
                log.append("greeting v").append(version).append(" methods=").append(nMethods).append(';');
                out.write(new byte[]{0x05, methodChoice});
                out.flush();

                if (authStatus != null) {
                    in.readUnsignedByte();                       // sub-negotiation version
                    byte[] user = new byte[in.readUnsignedByte()];
                    in.readFully(user);
                    byte[] pass = new byte[in.readUnsignedByte()];
                    in.readFully(pass);
                    log.append("auth ").append(new String(user, StandardCharsets.UTF_8))
                       .append(':').append(new String(pass, StandardCharsets.UTF_8)).append(';');
                    out.write(new byte[]{0x01, authStatus});
                    out.flush();
                    if (authStatus != 0x00) {
                        observed.set(log.toString());
                        return;
                    }
                }

                // CONNECT request
                in.readUnsignedByte();                           // version
                int cmd = in.readUnsignedByte();
                in.readUnsignedByte();                           // reserved
                int atyp = in.readUnsignedByte();
                byte[] host = new byte[in.readUnsignedByte()];
                in.readFully(host);
                int port = in.readUnsignedShort();
                log.append("connect cmd=").append(cmd).append(" atyp=").append(atyp)
                   .append(' ').append(new String(host, StandardCharsets.US_ASCII))
                   .append(':').append(port);
                observed.set(log.toString());

                // Reply with a bound IPv4 address.
                out.write(new byte[]{0x05, connectReply, 0x00, 0x01, 127, 0, 0, 1, 0x1F, (byte) 0x90});
                out.flush();

                if (connectReply == 0x00) {
                    // Echo one payload byte so the caller can prove the stream is usable.
                    InputStream tunnelled = socket.getInputStream();
                    int b = tunnelled.read();
                    if (b >= 0) {
                        out.write(b);
                        out.flush();
                    }
                }
                Thread.sleep(500);
            } catch (Exception ignored) {
                // test teardown closes the socket
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        return observed;
    }

    private SocketChannel connectToServer() throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.socket().connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                server.getLocalPort()), 5_000);
        return channel;
    }

    @Test
    void noAuth_completesHandshakeAndOpensTunnel() throws Exception {
        AtomicReference<String> observed = runServer((byte) 0x00, null, (byte) 0x00);

        try (SocketChannel channel = connectToServer()) {
            pump(channel, "target.example.com", 443, null, null);

            // Stream must now be positioned at tunnelled payload, not handshake leftovers.
            channel.socket().getOutputStream().write('X');
            assertThat(channel.socket().getInputStream().read()).isEqualTo('X');
        }

        assertThat(observed.get()).contains("connect cmd=1 atyp=3 target.example.com:443");
    }

    @Test
    void usernamePassword_isNegotiated() throws Exception {
        AtomicReference<String> observed = runServer((byte) 0x02, (byte) 0x00, (byte) 0x00);

        try (SocketChannel channel = connectToServer()) {
            pump(channel, "target.example.com", 8080, "alice", "s3cr:et");
        }

        assertThat(observed.get()).contains("auth alice:s3cr:et");
        assertThat(observed.get()).contains("target.example.com:8080");
    }

    @Test
    void rejectedCredentials_areReported() throws Exception {
        runServer((byte) 0x02, (byte) 0x01, (byte) 0x00);

        try (SocketChannel channel = connectToServer()) {
            assertThatThrownBy(() -> pump(channel, "h", 80, "bob", "wrong"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("rejected the supplied credentials");
        }
    }

    @Test
    void authRequiredButNoneConfigured_isReportedClearly() throws Exception {
        runServer((byte) 0x02, null, (byte) 0x00);

        try (SocketChannel channel = connectToServer()) {
            assertThatThrownBy(() -> pump(channel, "h", 80, null, null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("--proxy-userpwd");
        }
    }

    @Test
    void noAcceptableMethods_isReported() throws Exception {
        runServer((byte) 0xFF, null, (byte) 0x00);

        try (SocketChannel channel = connectToServer()) {
            assertThatThrownBy(() -> pump(channel, "h", 80, null, null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("rejected all offered authentication methods");
        }
    }

    @Test
    void connectRefusedByProxy_surfacesTheReplyCode() throws Exception {
        runServer((byte) 0x00, null, (byte) 0x05);

        try (SocketChannel channel = connectToServer()) {
            assertThatThrownBy(() -> pump(channel, "blocked.example", 443, null, null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("connection refused")
                    .hasMessageContaining("blocked.example:443");
        }
    }

    @Test
    void replyMessage_mapsKnownCodes() {
        assertThat(Socks5Handshake.replyMessage((byte) 0x02)).isEqualTo("connection not allowed by ruleset");
        assertThat(Socks5Handshake.replyMessage((byte) 0x04)).isEqualTo("host unreachable");
        assertThat(Socks5Handshake.replyMessage((byte) 0x7F)).contains("reply code");
    }

    /**
     * Drives the handshake to completion over a blocking socket, one exact-sized read per step,
     * the way the selector-driven connection does it asynchronously.
     */
    private static void pump(SocketChannel channel, String host, int port,
                             String user, String password) throws IOException {
        Socks5Handshake handshake = new Socks5Handshake(host, port, user, password);
        OutputStream out = channel.socket().getOutputStream();
        InputStream in = channel.socket().getInputStream();
        writeAll(out, handshake.greeting());

        while (!handshake.isDone()) {
            byte[] bytes = new byte[handshake.bytesNeeded()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) {
                    throw new IOException("proxy closed the connection during the handshake");
                }
                off += n;
            }
            ByteBuffer reply = handshake.accept(ByteBuffer.wrap(bytes));
            if (reply != null) {
                writeAll(out, reply);
            }
        }
    }

    private static void writeAll(OutputStream out, ByteBuffer buffer) throws IOException {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        out.write(bytes);
        out.flush();
    }
}
