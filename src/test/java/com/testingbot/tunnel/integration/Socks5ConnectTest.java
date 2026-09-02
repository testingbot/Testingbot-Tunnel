package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTPS CONNECT through a SOCKS5 upstream proxy, end to end: a real client socket, a real local
 * proxy started with {@code --proxy socks5://...}, and a hand-rolled SOCKS5 server.
 *
 * <p>{@link com.testingbot.tunnel.proxy.Socks5HandshakeTest} covers the wire format byte for
 * byte. What only this test can see is whether the handshake is wired into the CONNECT path
 * correctly -- that the dial promise is completed, that the tunnel carries traffic afterwards,
 * and that a proxy which never answers is given up on rather than waited out.
 */
class Socks5ConnectTest {

    private ServerSocket socks;
    private ExecutorService pool;
    private HttpProxy httpProxy;
    private int proxyPort;
    private final AtomicReference<String> observed = new AtomicReference<>("");
    private final CountDownLatch handshakeSeen = new CountDownLatch(1);

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpProxy != null) {
            httpProxy.stop();
        }
        if (socks != null && !socks.isClosed()) {
            socks.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A SOCKS5 server that, once the tunnel is open, plays the destination itself and echoes
     * whatever it receives -- so a test can prove the stream is usable and positioned at the
     * payload rather than at handshake leftovers.
     *
     * @param methodChoice   the authentication method to select
     * @param authStatus     the sub-negotiation result, or null if none is expected
     * @param connectReply   the CONNECT reply code
     * @param pipelined      bytes written in the same write as the reply
     */
    private void startSocks(byte methodChoice, Byte authStatus, byte connectReply,
                            String pipelined) throws IOException {
        socks = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (!socks.isClosed()) {
                Socket socket;
                try {
                    socket = socks.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> {
                    try (Socket client = socket) {
                        DataInputStream in = new DataInputStream(client.getInputStream());
                        OutputStream out = client.getOutputStream();
                        StringBuilder log = new StringBuilder();

                        in.readUnsignedByte();                       // version
                        byte[] methods = new byte[in.readUnsignedByte()];
                        in.readFully(methods);
                        out.write(new byte[]{0x05, methodChoice});
                        out.flush();

                        if (authStatus != null) {
                            in.readUnsignedByte();                   // sub-negotiation version
                            byte[] user = new byte[in.readUnsignedByte()];
                            in.readFully(user);
                            byte[] pass = new byte[in.readUnsignedByte()];
                            in.readFully(pass);
                            log.append("auth ").append(new String(user, StandardCharsets.UTF_8))
                                    .append(':').append(new String(pass, StandardCharsets.UTF_8))
                                    .append(';');
                            out.write(new byte[]{0x01, authStatus});
                            out.flush();
                            if (authStatus != 0x00) {
                                observed.set(log.toString());
                                handshakeSeen.countDown();
                                Thread.sleep(30_000);
                                return;
                            }
                        }

                        in.readUnsignedByte();                       // version
                        in.readUnsignedByte();                       // command
                        in.readUnsignedByte();                       // reserved
                        in.readUnsignedByte();                       // address type
                        byte[] host = new byte[in.readUnsignedByte()];
                        in.readFully(host);
                        int port = in.readUnsignedShort();
                        log.append("connect ").append(new String(host, StandardCharsets.US_ASCII))
                                .append(':').append(port);
                        observed.set(log.toString());
                        handshakeSeen.countDown();

                        // Reply, with a bound IPv4 address.
                        byte[] reply = {0x05, connectReply, 0x00, 0x01,
                                127, 0, 0, 1, 0x1F, (byte) 0x90};
                        if (pipelined == null) {
                            out.write(reply);
                        } else {
                            byte[] extra = pipelined.getBytes(StandardCharsets.US_ASCII);
                            byte[] both = new byte[reply.length + extra.length];
                            System.arraycopy(reply, 0, both, 0, reply.length);
                            System.arraycopy(extra, 0, both, reply.length, extra.length);
                            out.write(both);
                        }
                        out.flush();

                        if (connectReply == 0x00 && pipelined == null) {
                            byte[] buffer = new byte[4096];
                            int n;
                            while ((n = client.getInputStream().read(buffer)) > 0) {
                                out.write(buffer, 0, n);
                                out.flush();
                            }
                        } else {
                            Thread.sleep(30_000);
                        }
                    } catch (Exception ignored) {
                        // test finished
                    }
                });
            }
        });
    }

    private void startTunnel(String credentials) throws Exception {
        proxyPort = findFreePort();
        App app = new App();
        app.setJettyPort(proxyPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy("socks5://127.0.0.1:" + socks.getLocalPort());
        if (credentials != null) {
            app.setProxyAuth(credentials);
        }
        httpProxy = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    /** Opens a CONNECT through the tunnel; the returned socket is positioned after the headers. */
    private Socket connect(StringBuilder statusOut, int timeoutMs) throws Exception {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        socket.setSoTimeout(timeoutMs);
        socket.getOutputStream().write(("CONNECT target.example.com:443 HTTP/1.1\r\n"
                + "Host: target.example.com:443\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
        }
        statusOut.append(head);
        return socket;
    }

    @Test
    void aSocks5TunnelIsOpenedAndCarriesTraffic() throws Exception {
        startSocks((byte) 0x00, null, (byte) 0x00, null);
        startTunnel(null);

        StringBuilder status = new StringBuilder();
        try (Socket socket = connect(status, 20_000)) {
            assertThat(status.toString())
                    .as("a completed SOCKS5 handshake must be answered to the client")
                    .contains("200");

            // The destination is named to the proxy, not resolved here.
            assertThat(handshakeSeen.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get()).contains("connect target.example.com:443");

            socket.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] echoed = new byte[4];
            int got = 0;
            while (got < echoed.length) {
                int n = socket.getInputStream().read(echoed, got, echoed.length - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            assertThat(new String(echoed, StandardCharsets.US_ASCII)).isEqualTo("ping");
        }
    }

    @Test
    void credentialsAreNegotiatedWithAnAuthenticatingProxy() throws Exception {
        startSocks((byte) 0x02, (byte) 0x00, (byte) 0x00, null);
        startTunnel("alice:s3cret");

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect(status, 20_000)) {
            assertThat(status.toString()).contains("200");
        }
        assertThat(handshakeSeen.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(observed.get()).contains("auth alice:s3cret");
    }

    @Test
    void aRefusedConnectIsReportedAsAFailure() throws Exception {
        startSocks((byte) 0x00, null, (byte) 0x05, null);
        startTunnel(null);

        StringBuilder status = new StringBuilder();
        try (Socket ignored = connect(status, 20_000)) {
            // Not doesNotContain("200") alone: connect() appends nothing when the proxy answers
            // nothing, so an empty string satisfied that and the test passed with the
            // classified-error path deleted.
            assertThat(status.toString()).doesNotContain("200");
            assertThat(status.toString()).contains("502").contains("upstream-proxy-");
        }
    }

    @Test
    void aSilentSocks5ProxyDoesNotHangForever() throws Exception {
        // Accepts the TCP connection and never answers the greeting. The handshake has its own
        // idle timeout; inheriting the tunnel's, which is minutes long, would leave the client
        // waiting far past any use.
        socks = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            try (Socket accepted = socks.accept()) {
                Thread.sleep(90_000);
            } catch (Exception ignored) {
                // test finished
            }
        });
        startTunnel(null);

        StringBuilder status = new StringBuilder();
        long start = System.currentTimeMillis();
        try (Socket ignored = connect(status, 90_000)) {
            assertThat(status.toString()).doesNotContain("200");
            assertThat(System.currentTimeMillis() - start)
                    .as("should give up on the handshake timeout, not the tunnel's idle timeout")
                    .isLessThan(45_000);
        }
    }

    @Test
    void bytesPipelinedAfterTheReplyAreNotSwallowed() throws Exception {
        // The proxy may put the first bytes of the tunnelled stream in the same segment as its
        // reply. Each handshake step reads an exact byte count for this reason: a block read
        // would take those bytes and drop them, and the TLS handshake inside would fail with
        // nothing to explain it.
        String pipelined = "X".repeat(8192);
        startSocks((byte) 0x00, null, (byte) 0x00, pipelined);
        startTunnel(null);

        StringBuilder status = new StringBuilder();
        try (Socket socket = connect(status, 20_000)) {
            assertThat(status.toString()).contains("200");

            byte[] relayed = new byte[pipelined.length()];
            int got = 0;
            InputStream in = socket.getInputStream();
            while (got < relayed.length) {
                int n = in.read(relayed, got, relayed.length - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }

            assertThat(got).as("every pipelined byte should reach the client")
                    .isEqualTo(pipelined.length());
            assertThat(new String(relayed, StandardCharsets.US_ASCII)).isEqualTo(pipelined);
        }
    }
}
