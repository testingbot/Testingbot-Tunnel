package com.testingbot.tunnel.integration;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.HttpProxy;
import com.testingbot.tunnel.TestPorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ws://} leaves through {@code --proxy}, the way every other scheme does.
 *
 * <p>WebsocketHandler sits outside CustomConnectHandler in the chain and had no egress routing of
 * its own, so it dialled the target directly whatever {@code --proxy} said. On a network whose
 * only way out is a proxy -- the networks the option exists for -- {@code http://} and
 * {@code https://} worked and {@code ws://} hung until the dial timed out, then returned an error
 * that named nothing useful.
 */
class WebsocketThroughProxyTest {

    private ServerSocket origin;
    private ServerSocket proxy;
    private ExecutorService pool;
    private HttpProxy tunnel;
    private int proxyPort;
    private int originPort;
    private int tunnelPort;

    private final List<String> proxyLog = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) {
            tunnel.stop();
        }
        for (ServerSocket socket : new ServerSocket[]{origin, proxy}) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            // Clear a stale interrupt before waiting. MINA's and JSch's shutdown run
            // inline on this thread and can leave the flag set, which makes
            // awaitTermination throw immediately and fail teardown for a test that had
            // nothing to do with it -- seen in CI on
            // aLocalPortAlreadyInUseIsReportedAndLeavesForwardingIncomplete. A leftover
            // flag here is finished-test state, not a cancellation of the suite.
            Thread.interrupted();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Extra headers the origin adds to its 101, used to prove duplicates survive the relay. */
    private String extraOriginHeaders = "";

    /** An origin that completes one handshake and then echoes whatever it is sent. */
    private void startOrigin() throws Exception {
        originPort = TestPorts.free();
        origin = new ServerSocket(originPort, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!origin.isClosed()) {
                try (Socket client = origin.accept()) {
                    readHead(client.getInputStream());
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
                            + extraOriginHeaders + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    copy(client.getInputStream(), out);
                } catch (IOException done) {
                    return;
                }
            }
        });
    }

    /**
     * An HTTP proxy that records the request line and forwards to the origin.
     *
     * <p>It deliberately does not understand CONNECT: an upgrade needs none, and requiring one
     * here would let an implementation that tunnels first still pass.
     */
    private void startHttpProxy() throws Exception {
        proxyPort = TestPorts.free();
        proxy = new ServerSocket(proxyPort, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!proxy.isClosed()) {
                Socket accepted;
                try {
                    accepted = proxy.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> {
                    try (Socket client = accepted;
                         Socket upstream = new Socket("127.0.0.1", originPort)) {
                        String head = readHead(client.getInputStream());
                        proxyLog.add(head);
                        upstream.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
                        upstream.getOutputStream().flush();
                        InputStream fromClient = client.getInputStream();
                        OutputStream toUpstream = upstream.getOutputStream();
                        pool.submit(() -> copy(fromClient, toUpstream));
                        copy(upstream.getInputStream(), client.getOutputStream());
                    } catch (IOException ignored) {
                        // test finished
                    }
                });
            }
        });
    }

    /**
     * A proxy that grants CONNECT and relays, and refuses to forward an upgrade.
     *
     * <p>Which is how a stock Squid behaves: CONNECT works, and an Upgrade request is not
     * relayed without http_upgrade_request_protocols.
     */
    private void startConnectOnlyProxy() throws Exception {
        proxyPort = TestPorts.free();
        proxy = new ServerSocket(proxyPort, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!proxy.isClosed()) {
                Socket accepted;
                try {
                    accepted = proxy.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> {
                    try (Socket client = accepted) {
                        String head = readHead(client.getInputStream());
                        proxyLog.add(head);
                        OutputStream out = client.getOutputStream();
                        if (!head.startsWith("CONNECT ")) {
                            out.write(("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
                                    .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            return;
                        }
                        try (Socket upstream = new Socket("127.0.0.1", originPort)) {
                            out.write("HTTP/1.1 200 Connection established\r\n\r\n"
                                    .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            InputStream fromClient = client.getInputStream();
                            OutputStream toUpstream = upstream.getOutputStream();
                            pool.submit(() -> copy(fromClient, toUpstream));
                            copy(upstream.getInputStream(), out);
                        }
                    } catch (IOException ignored) {
                        // test finished
                    }
                });
            }
        });
    }

    /** A SOCKS5 proxy that records the destination it was asked for and then relays. */
    private void startSocks5(boolean requireAuth) throws Exception {
        proxyPort = TestPorts.free();
        proxy = new ServerSocket(proxyPort, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!proxy.isClosed()) {
                Socket accepted;
                try {
                    accepted = proxy.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> {
                    try (Socket client = accepted) {
                        java.io.DataInputStream in =
                                new java.io.DataInputStream(client.getInputStream());
                        OutputStream out = client.getOutputStream();

                        in.readUnsignedByte();                       // version
                        byte[] methods = new byte[in.readUnsignedByte()];
                        in.readFully(methods);
                        if (requireAuth) {
                            out.write(new byte[]{0x05, 0x02});
                            out.flush();
                            in.readUnsignedByte();                   // sub-negotiation version
                            byte[] user = new byte[in.readUnsignedByte()];
                            in.readFully(user);
                            byte[] password = new byte[in.readUnsignedByte()];
                            in.readFully(password);
                            proxyLog.add("auth " + new String(user, StandardCharsets.UTF_8) + ":"
                                    + new String(password, StandardCharsets.UTF_8));
                            out.write(new byte[]{0x01, 0x00});
                        } else {
                            out.write(new byte[]{0x05, 0x00});
                        }
                        out.flush();

                        in.readUnsignedByte();                       // version
                        in.readUnsignedByte();                       // command
                        in.readUnsignedByte();                       // reserved
                        int type = in.readUnsignedByte();
                        String host;
                        if (type == 0x01) {
                            byte[] address = new byte[4];
                            in.readFully(address);
                            host = InetAddress.getByAddress(address).getHostAddress();
                        } else {
                            byte[] name = new byte[in.readUnsignedByte()];
                            in.readFully(name);
                            host = new String(name, StandardCharsets.US_ASCII);
                        }
                        int port = in.readUnsignedShort();
                        proxyLog.add("connect " + host + ":" + port);

                        try (Socket upstream = new Socket(host, port)) {
                            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0});
                            out.flush();
                            InputStream fromClient = client.getInputStream();
                            OutputStream toUpstream = upstream.getOutputStream();
                            pool.submit(() -> copy(fromClient, toUpstream));
                            copy(upstream.getInputStream(), out);
                        }
                    } catch (Exception ignored) {
                        // test finished
                    }
                });
            }
        });
    }

    private void startTunnel(String proxySpec, String credentials) throws Exception {
        startTunnel(proxySpec, credentials, "get");
    }

    private void startTunnel(String proxySpec, String credentials, String wsProxyMode)
            throws Exception {
        tunnelPort = TestPorts.free();
        App app = new App();
        app.setJettyPort(tunnelPort);
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        app.setProxy(proxySpec);
        app.setWsProxyMode(wsProxyMode);
        if (credentials != null) {
            app.setProxyAuth(credentials);
        }
        tunnel = new HttpProxy(app);
        for (int i = 0; i < 100; i++) {
            try (Socket probe = new Socket("127.0.0.1", tunnelPort)) {
                return;
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("proxy did not start");
    }

    /** Upgrades through the tunnel, then writes a byte and reads it back. */
    private String upgrade() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", tunnelPort)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET http://127.0.0.1:" + originPort + "/ws HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + originPort + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            String head = readHead(in);
            if (!head.contains("101")) {
                return head;
            }
            out.write("relayed".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] buffer = new byte[64];
            int n = in.read(buffer);
            return head + (n > 0 ? new String(buffer, 0, n, StandardCharsets.UTF_8) : "");
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int c;
        while (head.indexOf("\r\n\r\n") < 0 && (c = in.read()) >= 0) {
            head.append((char) c);
        }
        return head.toString();
    }

    private static void copy(InputStream from, OutputStream to) {
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = from.read(buffer)) > 0) {
                to.write(buffer, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
            // connection closed
        }
    }

    @Test
    void anUpgradeTravelsThroughAnHttpUpstreamProxy() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startHttpProxy();
        startTunnel("http://127.0.0.1:" + proxyPort, null);

        String response = upgrade();

        assertThat(response).contains("101 Switching Protocols");
        assertThat(response).endsWith("relayed");
        // Absolute form is what makes it a proxy request rather than an origin request that
        // happened to arrive at the proxy's port.
        assertThat(proxyLog).hasSize(1);
        assertThat(proxyLog.get(0))
                .startsWith("GET http://127.0.0.1:" + originPort + "/ws HTTP/1.1");
    }

    @Test
    void proxyCredentialsAreSentWithTheUpgrade() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startHttpProxy();
        startTunnel("http://127.0.0.1:" + proxyPort, "alice:s3cret");

        assertThat(upgrade()).contains("101");

        // Pre-emptive, as on the CONNECT path: an upgrade cannot be replayed after a 407.
        assertThat(proxyLog.get(0)).contains("Proxy-Authorization: Basic "
                + java.util.Base64.getEncoder().encodeToString(
                        "alice:s3cret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theUpgradeIsNotSentToTheTargetInAbsoluteFormWithoutAProxy() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startTunnel(null, null);

        assertThat(upgrade()).contains("101").endsWith("relayed");
    }

    @Test
    void anUpgradeTravelsThroughASocks5UpstreamProxy() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startSocks5(false);
        startTunnel("socks5://127.0.0.1:" + proxyPort, null);

        String response = upgrade();

        assertThat(response).contains("101 Switching Protocols");
        assertThat(response).endsWith("relayed");
        assertThat(proxyLog).contains("connect 127.0.0.1:" + originPort);
    }

    @Test
    void socks5CredentialsAreNegotiatedBeforeTheUpgrade() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startSocks5(true);
        startTunnel("socks5://127.0.0.1:" + proxyPort, "alice:s3cret");

        assertThat(upgrade()).contains("101");

        // SOCKS authenticates inside its own handshake, so the credentials must not also appear
        // as a Proxy-Authorization header on the upgrade that follows.
        assertThat(proxyLog).contains("auth alice:s3cret");
        assertThat(proxyLog).contains("connect 127.0.0.1:" + originPort);
    }

    @Test
    void duplicateHandshakeResponseHeadersSurviveTheRelay() throws Exception {
        // The target's headers were collected into a LinkedHashMap<String,String> and replayed
        // with put(), so a 101 carrying two Set-Cookie -- a session cookie plus a load
        // balancer's affinity cookie is ordinary -- reached the client with only the last.
        pool = Executors.newCachedThreadPool();
        extraOriginHeaders = "Set-Cookie: session=abc\r\nSet-Cookie: affinity=node7\r\n";
        startOrigin();
        startTunnel(null, null);

        String response = upgrade();

        assertThat(response).contains("101");
        assertThat(response).contains("session=abc");
        assertThat(response).contains("affinity=node7");
    }

    @Test
    void theDefaultAsksTheProxyForATunnelAndUpgradesInsideIt() throws Exception {
        // RFC 6455 section 4.1: a client configured with a proxy should ask it to open a TCP
        // connection to the host, phrased for both schemes, with a worked example that is a
        // plain CONNECT to port 80. It is also what browsers do, so it is the default.
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startConnectOnlyProxy();
        startTunnel("http://127.0.0.1:" + proxyPort, null, "connect");

        String response = upgrade();

        assertThat(response).contains("101 Switching Protocols");
        assertThat(response).endsWith("relayed");
        assertThat(proxyLog).hasSize(1);
        assertThat(proxyLog.get(0)).startsWith("CONNECT 127.0.0.1:" + originPort);
        // Inside the tunnel the proxy is no longer the peer, so the upgrade must not be in
        // absolute form -- the target would see a request addressed to somebody else.
        assertThat(proxyLog.get(0)).doesNotContain("GET http://");
    }

    @Test
    void connectModeSendsProxyCredentialsWithTheConnect() throws Exception {
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startConnectOnlyProxy();
        startTunnel("http://127.0.0.1:" + proxyPort, "alice:s3cret", "connect");

        assertThat(upgrade()).contains("101");

        assertThat(proxyLog.get(0)).contains("Proxy-Authorization: Basic "
                + java.util.Base64.getEncoder().encodeToString(
                        "alice:s3cret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void getModeAgainstAProxyThatWillNotForwardAnUpgradeSaysSo() throws Exception {
        // The failure a stock Squid produces. The old message said "Target refused WebSocket
        // upgrade", blaming the origin for something the proxy did.
        pool = Executors.newCachedThreadPool();
        startOrigin();
        startConnectOnlyProxy();
        startTunnel("http://127.0.0.1:" + proxyPort, null, "get");

        String response = upgrade();

        assertThat(response).doesNotContain("101");
        assertThat(proxyLog.get(0)).startsWith("GET http://");
        // The proxy answered, so this is a refusal and not a connectivity problem: an operator
        // told "unreachable" goes and checks a connection that is working.
        assertThat(response).contains("upstream-proxy-refused");
    }
}
