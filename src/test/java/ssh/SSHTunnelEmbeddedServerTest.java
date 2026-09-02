package ssh;

import com.testingbot.tunnel.TestPorts;
import com.testingbot.tunnel.App;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSHTunnel driven against a real SSH server running in this JVM.
 *
 * <p>The JSch session handling -- authentication, both directions of port forwarding, the health
 * probes and shutdown -- was previously reachable only by connecting to the live TestingBot
 * service, so none of it ran in CI. Apache MINA SSHD speaks the actual protocol, so these
 * exercise the same code paths a real tunnel takes, minus the network and the account.
 *
 * <p>The tunnel server's port and the hub host are constructor parameters for this reason; in
 * production they are 443 and hub.testingbot.com.
 */
class SSHTunnelEmbeddedServerTest {

    private static final String USER = "tunnel-user";
    private static final String PASSWORD = "tunnel-secret";

    private SshServer sshd;
    private ServerSocket hub;
    private ServerSocket localProxy;
    private ExecutorService pool;
    private SSHTunnel tunnel;

    private static int findFreePort() throws IOException {
        return TestPorts.free();
    }

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        pool = Executors.newCachedThreadPool();

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((username, password, session) ->
                USER.equals(username) && PASSWORD.equals(password));
        // The tunnel's whole purpose is forwarding, so the server must permit it.
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tunnel != null) {
            tunnel.stop(true);
        }
        if (sshd != null) {
            sshd.stop(true);
        }
        closeQuietly(hub);
        closeQuietly(localProxy);
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    /** An App pointed at loopback, with credentials the embedded server accepts. */
    private App app() throws Exception {
        App app = new App();
        app.setClientKey(USER);
        app.setClientSecret(PASSWORD);
        app.setJettyPort(findFreePort());
        app.setSeleniumPort(findFreePort());
        return app;
    }

    private SSHTunnel connect(App app, String hubHost) throws Exception {
        return new SSHTunnel(app, "127.0.0.1", sshd.getPort(), hubHost);
    }

    /** A one-shot server that answers every connection with {@code body}. */
    private ServerSocket serverAnswering(String body) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    in.readLine();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // drain headers
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                            + "\r\nConnection: close\r\n\r\n" + body)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return server;
    }

    /* ---------------------------------------------------------------- authentication */

    @Test
    void connectsAndAuthenticates() throws Exception {
        App app = app();

        tunnel = connect(app, "127.0.0.1");

        assertThat(tunnel.isAuthenticated()).isTrue();
        assertThat(tunnel.getConnectionId()).hasSize(8);
        assertThat(tunnel.isShuttingDown()).isFalse();
    }

    @Test
    void wrongCredentialsFailWithAConnectionError() throws Exception {
        // The message a customer sees when their key or secret is wrong.
        App app = app();
        app.setClientSecret("not-the-password");

        assertThatThrownBy(() -> connect(app, "127.0.0.1"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Connection failed");
    }

    @Test
    void anUnreachableServerFailsRatherThanHanging() throws Exception {
        App app = app();
        int nothingListening = findFreePort();

        assertThatThrownBy(() -> new SSHTunnel(app, "127.0.0.1", nothingListening, "127.0.0.1"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Connection failed");
    }

    /* -------------------------------------------------------------- port forwarding */

    @Test
    void localForwardingCarriesTrafficToTheHub() throws Exception {
        // The Selenium path: a test connects to the local SSH port and lands on the hub.
        hub = serverAnswering("HUB-REACHED");
        App app = app();
        app.setHubPort(hub.getLocalPort());
        tunnel = connect(app, "127.0.0.1");

        tunnel.createPortForwarding();

        try (Socket client = new Socket("127.0.0.1", app.getSSHPort())) {
            client.setSoTimeout(10_000);
            client.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: hub\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            String response = new String(client.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).contains("HUB-REACHED");
        }
    }

    @Test
    void localForwardingIsVisibleToTheHealthCheck() throws Exception {
        hub = serverAnswering("HUB");
        App app = app();
        app.setHubPort(hub.getLocalPort());
        tunnel = connect(app, "127.0.0.1");

        tunnel.createPortForwarding();

        // The same check the 15-second monitor makes, now against a real session.
        assertThat(SSHTunnel.localForwardingActive(
                tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isTrue();
        assertThat(SSHTunnel.localForwardingActive(
                tunnel.getSession().getPortForwardingL(), findFreePort())).isFalse();
    }

    @Test
    void reverseForwardingIsRegisteredOnTheServer() throws Exception {
        // The browser-traffic direction: the tunnel server accepts on 2010 and delivers here.
        hub = serverAnswering("HUB");
        localProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        App app = app();
        app.setHubPort(hub.getLocalPort());
        app.setJettyPort(localProxy.getLocalPort());
        tunnel = connect(app, "127.0.0.1");

        tunnel.createPortForwarding();

        assertThat(tunnel.getSession().getPortForwardingR())
                .anyMatch(forward -> forward.contains("2010"));
    }

    /* ----------------------------------------------------- reverse delivery probe */

    @Test
    void reverseDeliveryIsHealthyWhenTheLocalProxyIsListening() throws Exception {
        // TB-253: the reverse forward has no JSch-side status, so delivery is probed directly.
        localProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        App app = app();
        app.setJettyPort(localProxy.getLocalPort());
        tunnel = connect(app, "127.0.0.1");

        assertThat(tunnel.verifyReverseForwardDelivery()).isTrue();
    }

    @Test
    void reverseDeliveryIsBrokenWhenNothingIsListening() throws Exception {
        App app = app();
        app.setJettyPort(findFreePort());   // nothing bound
        tunnel = connect(app, "127.0.0.1");

        assertThat(tunnel.verifyReverseForwardDelivery()).isFalse();
    }

    /* -------------------------------------------------------------------- lifecycle */

    @Test
    void stopDisconnectsTheSession() throws Exception {
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        assertThat(tunnel.isAuthenticated()).isTrue();

        tunnel.stop();

        assertThat(tunnel.isAuthenticated()).isFalse();
    }

    @Test
    void stopWhileQuittingMarksTheTunnelAsShuttingDown() throws Exception {
        // The reconnect monitor keys off this: a drop during shutdown must not be recovered.
        App app = app();
        tunnel = connect(app, "127.0.0.1");

        tunnel.stop(true);

        assertThat(tunnel.isShuttingDown()).isTrue();
        assertThat(tunnel.isAuthenticated()).isFalse();
    }

    @Test
    void stopIsSafeToCallTwice() throws Exception {
        App app = app();
        tunnel = connect(app, "127.0.0.1");

        tunnel.stop();
        tunnel.stop();

        assertThat(tunnel.isAuthenticated()).isFalse();
    }

    @Test
    void aTunnelCanBeReconnectedAfterBeingStopped() throws Exception {
        // What CustomConnectionMonitor does on every retry.
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        tunnel.stop();
        assertThat(tunnel.isAuthenticated()).isFalse();

        tunnel.connect();

        assertThat(tunnel.isAuthenticated()).isTrue();
    }

    /* --------------------------------------------------------------- health monitors */

    @Test
    void keepAliveSucceedsAgainstALiveSession() throws Exception {
        // Runs every 30s in production, which no test would ever wait for; driven directly it
        // exercises the real sendKeepAliveMsg round trip.
        App app = app();
        tunnel = connect(app, "127.0.0.1");

        tunnel.new KeepAliveTask().run();

        assertThat(tunnel.isAuthenticated()).isTrue();
    }

    @Test
    void keepAliveOnADeadSessionIsSwallowed() throws Exception {
        // A TimerTask that throws is never rescheduled, so an escaping exception here would
        // silently end all keep-alives for the life of the tunnel.
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        tunnel.stop();

        assertThatCode(() -> tunnel.new KeepAliveTask().run()).doesNotThrowAnyException();
    }

    @Test
    void theConnectionMonitorIsQuietWhileTheSessionIsUp() throws Exception {
        App app = app();
        tunnel = connect(app, "127.0.0.1");

        tunnel.new ConnectionMonitorTask().run();

        assertThat(tunnel.isAuthenticated()).isTrue();
    }

    @Test
    void theConnectionMonitorDoesNotReconnectDuringShutdown() throws Exception {
        // stop(true) marks the tunnel as shutting down; recovering then would fight the
        // shutdown and could leave a tunnel registered server-side.
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        tunnel.stop(true);

        assertThatCode(() -> tunnel.new ConnectionMonitorTask().run()).doesNotThrowAnyException();
        assertThat(tunnel.isAuthenticated()).isFalse();
    }

    @Test
    void thePortForwardingMonitorIsQuietWhenEverythingIsHealthy() throws Exception {
        hub = serverAnswering("HUB");
        localProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        App app = app();
        app.setHubPort(hub.getLocalPort());
        app.setJettyPort(localProxy.getLocalPort());
        tunnel = connect(app, "127.0.0.1");
        tunnel.createPortForwarding();

        tunnel.new PortForwardingMonitorTask().run();

        assertThat(SSHTunnel.localForwardingActive(
                tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isTrue();
    }

    @Test
    void thePortForwardingMonitorRestoresALostLocalForward() throws Exception {
        // The failure it exists to catch: the forward disappears while the session stays up.
        hub = serverAnswering("HUB");
        localProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        App app = app();
        app.setHubPort(hub.getLocalPort());
        app.setJettyPort(localProxy.getLocalPort());
        tunnel = connect(app, "127.0.0.1");
        tunnel.createPortForwarding();

        tunnel.getSession().delPortForwardingL(app.getSSHPort());
        assertThat(SSHTunnel.localForwardingActive(
                tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isFalse();

        tunnel.new PortForwardingMonitorTask().run();

        assertThat(SSHTunnel.localForwardingActive(
                tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isTrue();
    }

    @Test
    void thePortForwardingMonitorIsQuietOnADeadSession() throws Exception {
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        tunnel.stop();

        assertThatCode(() -> tunnel.new PortForwardingMonitorTask().run())
                .doesNotThrowAnyException();
    }

    /* --------------------------------------------------------- forwarding failures */

    @Test
    void aLocalPortAlreadyInUseIsReportedAndLeavesForwardingIncomplete() throws Exception {
        // Two tunnels on one machine, or anything else already on --se-port. The forward
        // cannot be made, and pretending otherwise would leave Selenium quietly broken.
        hub = serverAnswering("HUB");
        App app = app();
        app.setHubPort(hub.getLocalPort());
        tunnel = connect(app, "127.0.0.1");

        try (ServerSocket squatter = new ServerSocket(app.getSSHPort(), 50,
                InetAddress.getLoopbackAddress())) {
            tunnel.createPortForwarding();

            assertThat(SSHTunnel.localForwardingActive(
                    tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isFalse();
        }
    }

    @Test
    void theReverseForwardFailingDoesNotSkipTheLocalOne() throws Exception {
        // A second tunnel to the same server cannot bind remote 2010 -- it is already taken by
        // the first. The local forward must still be established: doing both in one try block
        // is what made the restart path unable to recover a lost forward.
        hub = serverAnswering("HUB");
        localProxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        App first = app();
        first.setHubPort(hub.getLocalPort());
        first.setJettyPort(localProxy.getLocalPort());
        tunnel = connect(first, "127.0.0.1");
        tunnel.createPortForwarding();

        App second = app();
        second.setHubPort(hub.getLocalPort());
        second.setJettyPort(localProxy.getLocalPort());
        SSHTunnel other = connect(second, "127.0.0.1");
        try {
            other.createPortForwarding();

            assertThat(SSHTunnel.localForwardingActive(
                    other.getSession().getPortForwardingL(), second.getSSHPort()))
                    .as("the local forward must survive the reverse forward failing")
                    .isTrue();
        } finally {
            other.stop(true);
        }
    }

    /* ------------------------------------------------------- health state changes */

    @Test
    void theMonitorReportsReverseDeliveryBreakingAndRecovering() throws Exception {
        // Only transitions are logged, so both directions of the change must be exercised.
        hub = serverAnswering("HUB");
        App app = app();
        app.setHubPort(hub.getLocalPort());
        app.setJettyPort(findFreePort());   // nothing listening: delivery is broken
        tunnel = connect(app, "127.0.0.1");
        tunnel.createPortForwarding();

        SSHTunnel.PortForwardingMonitorTask monitor = tunnel.new PortForwardingMonitorTask();
        monitor.run();
        assertThat(tunnel.verifyReverseForwardDelivery()).isFalse();

        // Bring the local proxy up on the port the reverse forward delivers to.
        try (ServerSocket recovered = new ServerSocket(app.getJettyPort(), 50,
                InetAddress.getLoopbackAddress())) {
            assertThat(tunnel.verifyReverseForwardDelivery()).isTrue();
            monitor.run();

            assertThat(SSHTunnel.localForwardingActive(
                    tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isTrue();
        }
    }

    @Test
    void theConnectionMonitorNoticesTheServerGoingAway() throws Exception {
        // The path that triggers a reconnect: session gone, and not a deliberate shutdown.
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        sshd.stop(true);
        for (int i = 0; i < 100 && tunnel.isAuthenticated(); i++) {
            Thread.sleep(50);
        }

        assertThatCode(() -> tunnel.new ConnectionMonitorTask().run()).doesNotThrowAnyException();

        assertThat(tunnel.isShuttingDown()).isFalse();
    }

    @Test
    void losingTheServerIsVisibleAsLostAuthentication() throws Exception {
        App app = app();
        tunnel = connect(app, "127.0.0.1");
        assertThat(tunnel.isAuthenticated()).isTrue();

        sshd.stop(true);

        // JSch notices asynchronously; the monitor polls for exactly this.
        for (int i = 0; i < 100 && tunnel.isAuthenticated(); i++) {
            Thread.sleep(50);
        }
        assertThat(tunnel.isAuthenticated()).isFalse();
    }
}
