package ssh;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.Await;
import com.testingbot.tunnel.ForwardProbeHandler;
import com.testingbot.tunnel.TestPorts;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * --ssh-connections against a real SSH server in this JVM, with a stand-in for the tunnel
 * server's tunnel-mux (ansible tunnel02/scripts/tunnel-mux.py): it serves the tunnel's port only
 * once a forward on the multiplexed ports answers the probe, and spreads connections over the
 * forwards that do.
 *
 * <p>The two directions of compatibility are what matter: a server without tunnel-mux (every
 * production image so far) must get exactly the single connection on the tunnel port that every
 * client before used, and one connection asked for must never touch the multiplexed ports.
 */
class SSHTunnelMultiConnectionTest {

    private static final String USER = "tunnel-user";
    private static final String PASSWORD = "tunnel-secret";

    private SshServer sshd;
    private ExecutorService pool;
    private ServerSocket localProxy;
    private final AtomicInteger proxied = new AtomicInteger();
    private SSHTunnel tunnel;
    private StandInMux mux;
    private int tunnelPort;
    private int muxFirstPort;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        pool = Executors.newCachedThreadPool();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((username, password, session) ->
                USER.equals(username) && PASSWORD.equals(password));
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.start();
        tunnelPort = TestPorts.free();
        muxFirstPort = freeRange(SSHTunnel.MUX_PORT_COUNT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) {
            tunnel.stop(true);
        }
        if (mux != null) {
            mux.close();
        }
        if (localProxy != null) {
            localProxy.close();
        }
        sshd.stop(true);
        pool.shutdownNow();
        Thread.interrupted();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void opensEveryConnectionWhenTheServerSpreadsThePort() throws Exception {
        mux = new StandInMux(tunnelPort, muxFirstPort);
        App app = app(3);
        tunnel = connect(app);

        tunnel.createPortForwarding();
        tunnel.settleForwarding();

        assertThat(tunnel.isMuxed()).isTrue();
        assertThat(tunnel.isForwardingEstablished()).isTrue();
        Await.until("all three connections forwarding", () -> tunnel.forwardingConnections() == 3);
        Set<Integer> ports = new HashSet<>();
        ports.add(tunnel.getBoundRemotePort());
        for (SSHTunnel extra : tunnel.getExtras()) {
            ports.add(extra.getBoundRemotePort());
        }
        assertThat(ports).hasSize(3)
                .allSatisfy(p -> assertThat(p).isBetween(muxFirstPort, muxFirstPort + SSHTunnel.MUX_PORT_COUNT - 1));
        // the hub forward is a local port: only the first connection holds it
        for (SSHTunnel extra : tunnel.getExtras()) {
            assertThat(SSHTunnel.localForwardingActive(extra.getSession().getPortForwardingL(), app.getSSHPort())).isFalse();
        }

        Await.until("the stand-in sees every forward", () -> mux.healthy() == 3);
        for (int i = 0; i < 30; i++) {
            assertThat(get(tunnelPort)).isEqualTo("proxied");
        }
        assertThat(mux.usedBackends()).hasSize(3);
    }

    @Test
    void theForwardsStayPutAcrossForwardingChecks() throws Exception {
        mux = new StandInMux(tunnelPort, muxFirstPort);
        App app = app(3);
        tunnel = connect(app);
        tunnel.createPortForwarding();
        tunnel.settleForwarding();
        Await.until("all three connections forwarding", () -> tunnel.forwardingConnections() == 3);
        List<Integer> before = boundPorts();

        // two runs of each connection's forwarding monitor
        Thread.sleep(2 * SSHTunnel.FORWARDING_CHECK_INTERVAL_MS + 1_000);

        assertThat(boundPorts()).isEqualTo(before);
        assertThat(tunnel.forwardingConnections()).isEqualTo(3);
        // nothing was re-registered: on a real server a re-registered forward often lands on
        // another port while the old one is still being released (seen in the lab)
        assertThat(tunnel.getForwardRestarts()).isZero();
        assertThat(tunnel.getExtras()).allSatisfy(extra -> assertThat(extra.getForwardRestarts()).isZero());
    }

    private List<Integer> boundPorts() {
        List<Integer> ports = new ArrayList<>();
        ports.add(tunnel.getBoundRemotePort());
        for (SSHTunnel extra : tunnel.getExtras()) {
            ports.add(extra.getBoundRemotePort());
        }
        return ports;
    }

    @Test
    void aServerWithoutTunnelMuxGetsOneConnectionOnTheTunnelPort() throws Exception {
        App app = app(4);
        tunnel = connect(app);

        tunnel.createPortForwarding();
        long start = System.currentTimeMillis();
        tunnel.settleForwarding();

        assertThat(System.currentTimeMillis() - start).isLessThan(SSHTunnel.MUX_CONFIRM_MS + 2_000L);
        assertThat(tunnel.isMuxed()).isFalse();
        assertThat(tunnel.getBoundRemotePort()).isEqualTo(tunnelPort);
        assertThat(tunnel.isForwardingEstablished()).isTrue();
        assertThat(tunnel.getExtras()).isEmpty();
        assertThat(tunnel.getSession().getPortForwardingR())
                .noneMatch(f -> f.startsWith(muxFirstPort + ":") || f.contains(":" + muxFirstPort + ":"));
        assertThat(get(tunnelPort)).isEqualTo("proxied");

        // a reconnect on the same server does not try again
        tunnel.restartPortForwarding();
        assertThat(tunnel.getBoundRemotePort()).isEqualTo(tunnelPort);
    }

    @Test
    void oneConnectionAskedForNeverTouchesTheMultiplexedPorts() throws Exception {
        mux = new StandInMux(tunnelPort, muxFirstPort);
        App app = app(1);
        tunnel = connect(app);

        tunnel.createPortForwarding();
        tunnel.settleForwarding();

        assertThat(tunnel.getBoundRemotePort()).isEqualTo(tunnelPort);
        assertThat(tunnel.getExtras()).isEmpty();
        assertThat(mux.healthy()).isZero();
        assertThat(get(tunnelPort)).isEqualTo("proxied");
    }

    @Test
    void theOtherConnectionsCarryTrafficWhileTheFirstReconnects() throws Exception {
        mux = new StandInMux(tunnelPort, muxFirstPort);
        App app = app(3);
        tunnel = connect(app);
        tunnel.createPortForwarding();
        tunnel.settleForwarding();
        Await.until("all three connections forwarding", () -> tunnel.forwardingConnections() == 3);
        Await.until("the stand-in sees every forward", () -> mux.healthy() == 3);
        int firstPort = tunnel.getBoundRemotePort();

        tunnel.getSession().disconnect();
        Await.until("the stand-in drops the first connection's forward", () -> !mux.isHealthy(firstPort));

        assertThat(tunnel.isAnyConnectionForwarding()).isTrue();
        for (int i = 0; i < 10; i++) {
            assertThat(get(tunnelPort)).isEqualTo("proxied");
        }
        Await.until("the first connection is back", 15_000, () -> tunnel.isForwardingEstablished());
        assertThat(tunnel.getBoundRemotePort())
                .isBetween(muxFirstPort, muxFirstPort + SSHTunnel.MUX_PORT_COUNT - 1);
    }

    @Test
    void anExtraConnectionThatDropsReconnectsOnItsOwn() throws Exception {
        mux = new StandInMux(tunnelPort, muxFirstPort);
        App app = app(2);
        tunnel = connect(app);
        tunnel.createPortForwarding();
        tunnel.settleForwarding();
        Await.until("both connections forwarding", () -> tunnel.forwardingConnections() == 2);
        SSHTunnel extra = tunnel.getExtras().get(0);

        extra.getSession().disconnect();

        Await.until("the extra connection is back", 15_000,
                () -> extra.isForwardingEstablished() && extra.getSession().isConnected());
        assertThat(tunnel.isForwardingEstablished()).isTrue();
        assertThat(tunnel.getExtras()).hasSize(1);
    }

    /* ---------------------------------------------------------------- helpers */

    private App app(int connections) throws Exception {
        App app = new App();
        app.setClientKey(USER);
        app.setClientSecret(PASSWORD);
        app.setJettyPort(TestPorts.free());
        app.setSeleniumPort(TestPorts.free());
        app.setSshConnections(connections);
        localProxy = proxyAnswering(app.getJettyPort());
        return app;
    }

    private SSHTunnel connect(App app) throws Exception {
        ServerSocket hub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!hub.isClosed()) {
                try (Socket ignored = hub.accept()) {
                    // never used here
                } catch (IOException closed) {
                    return;
                }
            }
        });
        app.setHubPort(hub.getLocalPort());
        return new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1", tunnelPort, muxFirstPort);
    }

    /** The client's local proxy: answers the forward probe like ForwardProbeHandler, anything else "proxied". */
    private ServerSocket proxyAnswering(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        pool.submit(() -> {
            while (!server.isClosed()) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException closed) {
                    return;
                }
                pool.submit(() -> answer(socket));
            }
        });
        return server;
    }

    private void answer(Socket socket) {
        try (socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String nonce = null;
            String line = in.readLine();
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith(ForwardProbeHandler.PROBE_HEADER.toLowerCase(Locale.ROOT) + ":")) {
                    nonce = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            OutputStream out = socket.getOutputStream();
            if (nonce != null) {
                out.write(("HTTP/1.1 204 No Content\r\n" + ForwardProbeHandler.PROBE_HEADER + ": " + nonce
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            } else {
                proxied.incrementAndGet();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nproxied"
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            out.flush();
        } catch (IOException ignored) {
            // the peer went away
        }
    }

    /** One plain request into {@code port}; the body, or the failure. */
    private static String get(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            s.setSoTimeout(5_000);
            s.getOutputStream().write("GET http://app.local/ HTTP/1.1\r\nHost: app.local\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            String answer = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            return answer.substring(answer.indexOf("\r\n\r\n") + 4);
        } catch (IOException failed) {
            return "failed: " + failed.getMessage();
        }
    }

    private static int freeRange(int count) throws IOException {
        java.util.Random random = new java.util.Random();
        for (int attempt = 0; attempt < 200; attempt++) {
            int base = 33_000 + random.nextInt(20_000);
            boolean free = true;
            for (int p = base; p < base + count && free; p++) {
                try (ServerSocket probe = new ServerSocket()) {
                    probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), p));
                } catch (IOException taken) {
                    free = false;
                }
            }
            if (free) {
                return base;
            }
        }
        throw new IOException("no free range of " + count + " ports");
    }

    /**
     * What tunnel-mux does, minimally: probe each multiplexed port, serve the tunnel port once one
     * answers, and hand each connection to the next healthy forward.
     */
    private final class StandInMux implements Closeable {
        private final int listenPort;
        private final int firstPort;
        private final Set<Integer> healthy = ConcurrentHashMap.newKeySet();
        private final Set<Integer> used = ConcurrentHashMap.newKeySet();
        private volatile ServerSocket server;
        private volatile boolean closed;
        private final AtomicInteger next = new AtomicInteger();

        StandInMux(int listenPort, int firstPort) {
            this.listenPort = listenPort;
            this.firstPort = firstPort;
            pool.submit(this::probeForever);
        }

        int healthy() {
            return healthy.size();
        }

        boolean isHealthy(int port) {
            return healthy.contains(port);
        }

        Set<Integer> usedBackends() {
            return used;
        }

        private void probeForever() {
            while (!closed) {
                for (int p = firstPort; p < firstPort + SSHTunnel.MUX_PORT_COUNT; p++) {
                    if (probe(p)) {
                        healthy.add(p);
                    } else {
                        healthy.remove(p);
                    }
                }
                if (!healthy.isEmpty() && server == null) {
                    bind();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }

        private boolean probe(int port) {
            String nonce = UUID.randomUUID().toString();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                s.setSoTimeout(1_000);
                s.getOutputStream().write(("GET http://" + ForwardProbeHandler.PROBE_HOST + "/ HTTP/1.1\r\nHost: "
                        + ForwardProbeHandler.PROBE_HOST + "\r\n" + ForwardProbeHandler.PROBE_HEADER + ": " + nonce
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                String answer = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                return answer.contains(nonce);
            } catch (IOException failed) {
                return false;
            }
        }

        private void bind() {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), listenPort));
                server = socket;
                pool.submit(this::acceptForever);
            } catch (IOException taken) {
                // the client bound the tunnel port itself; nothing to take over
            }
        }

        private void acceptForever() {
            while (!closed) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException stop) {
                    return;
                }
                pool.submit(() -> relay(client));
            }
        }

        private void relay(Socket client) {
            List<Integer> live = new ArrayList<>(healthy);
            if (live.isEmpty()) {
                closeQuietly(client);
                return;
            }
            live.sort(null);
            int port = live.get(Math.floorMod(next.getAndIncrement(), live.size()));
            try {
                Socket backend = new Socket("127.0.0.1", port);
                used.add(port);
                pool.submit(() -> pipe(client, backend));
                pool.submit(() -> pipe(backend, client));
            } catch (IOException failed) {
                closeQuietly(client);
            }
        }

        private void pipe(Socket from, Socket to) {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                in.transferTo(out);
                to.shutdownOutput();
            } catch (IOException ignored) {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (server != null) {
                server.close();
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
