package com.testingbot.tunnel;

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
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A whole tunnel booted inside another application's process.
 *
 * <p>{@link AppEmbeddedTest} covers the failure paths -- that a broken tunnel reports to its
 * caller instead of calling {@code System.exit}. Nothing covered a tunnel that <em>works</em>
 * embedded: every other test drives one component, and the command line is the only path that
 * ever ran the whole of {@code boot()}. So the things that only go wrong when an App shares a
 * JVM -- process-wide readiness, a JVM-wide authenticator, listeners and shutdown hooks that
 * outlive their App, a second tunnel alongside the first -- had nowhere to fail.
 *
 * <p>The API is WireMock and the tunnel server is Apache MINA SSHD in this JVM, reached through
 * the {@code createApi} and {@code createTunnel} seams. Two of the three startup checks are the
 * real ones: the Selenium relay reaches a hub stand-in through the SSH local forward, and the
 * reverse forward reaches the local proxy.
 */
class AppEmbeddedLifecycleTest {

    private static final String KEY = "embedded_key";
    private static final String SECRET = "embedded_secret";

    private ServerSocket api;
    /** One per App: in production every tunnel gets its own tunnel server. */
    private final List<SshServer> sshServers = new ArrayList<>();
    private Path keyDirectory;
    private ServerSocket hub;
    private ExecutorService pool;
    private final List<App> started = new ArrayList<>();
    private Authenticator authenticatorBefore;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        authenticatorBefore = Authenticator.getDefault();
        TunnelMetrics.setTunnelUp(false);
        pool = Executors.newCachedThreadPool();

        api = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(this::serveApi);

        keyDirectory = tmp;

        // What the Selenium relay's forward is delivered to: answers 200 to the HEAD that the
        // forwarding self-test makes.
        hub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!hub.isClosed()) {
                try (Socket socket = hub.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = in.readLine();
                    while (line != null && !line.isEmpty()) {
                        line = in.readLine();
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        for (App app : started) {
            assertThatCode(app::stop).doesNotThrowAnyException();
        }
        started.clear();
        for (SshServer server : sshServers) {
            server.stop(true);
        }
        sshServers.clear();
        if (hub != null && !hub.isClosed()) {
            hub.close();
        }
        if (api != null && !api.isClosed()) {
            api.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            Thread.interrupted();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        Authenticator.setDefault(authenticatorBefore);
        TunnelMetrics.setTunnelUp(false);
    }

    /**
     * A stand-in for the TestingBot API: creates the tunnel, answers polls, and -- the part that
     * matters here -- performs the callback the startup self-test asks for.
     *
     * <p>{@code testProxy()} starts an ephemeral local server, posts its port, and expects the
     * service to fetch it and echo what it served. Answering with a canned body would leave the
     * third readiness check untested, which is the one that decides whether an embedded tunnel
     * reports ready at all. This one really fetches. It dials the port directly rather than
     * through TestingBot's own network, which is the single piece of the loop no in-process test
     * can supply.
     */
    private void serveApi() {
        while (!api.isClosed()) {
            try (Socket socket = api.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(10_000);
                String requestLine = in.readLine();
                if (requestLine == null) {
                    continue;
                }
                int contentLength = 0;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":", 2)[1].trim());
                    }
                }
                char[] body = new char[contentLength];
                if (contentLength > 0) {
                    in.read(body, 0, contentLength);
                }
                respondTo(socket, requestLine, new String(body));
            } catch (Exception closed) {
                if (api.isClosed()) {
                    return;
                }
            }
        }
    }

    private void respondTo(Socket socket, String requestLine, String body) throws IOException {
        String tunnel = "{\"id\":\"9001\",\"state\":\"READY\",\"ip\":\"127.0.0.1\"}";
        if (requestLine.contains("/v1/tunnel/test")) {
            String served = fetchCallback(formValue(body, "test_port"));
            write(socket, 201, served);
            return;
        }
        write(socket, 200, tunnel);
    }

    /** Fetches the ephemeral server testProxy() stood up, and returns what it served. */
    private String fetchCallback(String port) {
        if (port == null) {
            return "";
        }
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(port))) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int blank = response.indexOf("\r\n\r\n");
            return blank < 0 ? "" : response.substring(blank + 4);
        } catch (Exception unreachable) {
            return "";
        }
    }

    private static String formValue(String body, String name) {
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return null;
    }

    private static void write(Socket socket, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    /** An App wired to the in-process API and tunnel server, as an embedder would configure it. */
    private SshServer newTunnelServer() throws IOException {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(
                keyDirectory.resolve("hostkey-" + sshServers.size() + ".ser")));
        sshd.setPasswordAuthenticator((user, password, session) ->
                KEY.equals(user) && SECRET.equals(password));
        sshd.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        sshd.start();
        sshServers.add(sshd);
        return sshd;
    }

    private App embeddedApp() throws IOException {
        SshServer sshd = newTunnelServer();
        // 2010 in production, where each tunnel server is its own machine. Here they share one.
        int remoteProxyPort = TestPorts.free();
        App app = new App() {
            @Override
            Api createApi() {
                Api created = new Api(this);
                created.setApiScheme("http");
                created.setApiHost("127.0.0.1:" + api.getLocalPort());
                return created;
            }

            @Override
            ssh.SSHTunnel createTunnel(String serverIp) throws Exception {
                return ssh.TestTunnels.connect(this, serverIp, sshd.getPort(), "127.0.0.1",
                        remoteProxyPort);
            }
        };
        app.setClientKey(KEY);
        app.setClientSecret(SECRET);
        app.setSeleniumPort(TestPorts.free());
        app.setJettyPort(TestPorts.free());
        app.setMetricsPort(TestPorts.free());
        app.setHubPort(hub.getLocalPort());
        started.add(app);
        return app;
    }

    private App boot() throws Exception {
        App app = embeddedApp();
        app.boot();
        return app;
    }

    /** GETs a path on an App's metrics port and returns the status code. */
    private static int probe(App app, String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", app.getMetricsPort());
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String status = in.readLine();
            return status == null ? -1 : Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static boolean portIsFree(int port) {
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException stillBound) {
            return false;
        }
    }

    @Test
    void aTunnelBootsAndReportsItselfReady() throws Exception {
        App app = boot();

        assertThat(app.isReady())
                .as("the self-test's checks pass against the in-process hub and proxy")
                .isTrue();
        assertThat(probe(app, "/readyz")).isEqualTo(200);
        assertThat(probe(app, "/healthz")).isEqualTo(200);
    }

    @Test
    void stoppingReleasesEveryListenerItBound() throws Exception {
        App app = boot();
        int metricsPort = app.getMetricsPort();
        int proxyPort = app.getJettyPort();
        int seleniumPort = app.getSeleniumPort();

        app.stop();

        // A host application that runs a tunnel per job gets the ports back, or the next job
        // cannot bind them.
        Await.until("the metrics port to be released", () -> portIsFree(metricsPort));
        Await.until("the local proxy port to be released", () -> portIsFree(proxyPort));
        Await.until("the Selenium relay port to be released", () -> portIsFree(seleniumPort));
        assertThat(app.isReady()).isFalse();
    }

    @Test
    void aTunnelPerJobCanBeBootedAgainInTheSameProcess() throws Exception {
        App first = boot();
        assertThat(first.isReady()).isTrue();
        first.stop();

        App second = boot();

        assertThat(second.isReady())
                .as("nothing the first tunnel installed may stop the second from working")
                .isTrue();
        assertThat(probe(second, "/readyz")).isEqualTo(200);
    }

    @Test
    void twoTunnelsInOneProcessReportTheirOwnReadiness() throws Exception {
        // The reason readiness is per App. Both endpoints answered from one process-wide gauge,
        // so a host running two tunnels saw them as a single tunnel -- and stopping either one
        // reported the other as not ready.
        App first = boot();
        App second = boot();
        assertThat(probe(first, "/readyz")).isEqualTo(200);
        assertThat(probe(second, "/readyz")).isEqualTo(200);

        first.stop();

        assertThat(first.isReady()).isFalse();
        assertThat(second.isReady())
                .as("stopping one tunnel must not report the other as down")
                .isTrue();
        assertThat(probe(second, "/readyz")).isEqualTo(200);
    }

    @Test
    void stoppingPutsBackTheAuthenticatorItReplaced() throws Exception {
        // Authenticator.setDefault is JVM-wide with no other hook, so a tunnel that does not put
        // back what it found leaves a host application's own credentials answered by ours.
        Authenticator hostApplications = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("host", "app".toCharArray());
            }
        };
        Authenticator.setDefault(hostApplications);

        App app = embeddedApp();
        app.setProxy("127.0.0.1:1");
        app.setProxyAuth("tunnel-user:tunnel-password");
        assertThat(Authenticator.getDefault()).isNotSameAs(hostApplications);

        app.stop();

        assertThat(Authenticator.getDefault()).isSameAs(hostApplications);
    }

    @Test
    void configuringProxyCredentialsTwiceDoesNotStackAuthenticators() {
        Authenticator hostApplications = Authenticator.getDefault();

        App app = new App();
        app.setProxy("127.0.0.1:1");
        app.setProxyAuth("user:one");
        Authenticator afterFirst = Authenticator.getDefault();
        app.setProxyAuth("user:two");

        assertThat(Authenticator.getDefault()).isNotSameAs(afterFirst);
        app.stop();
        assertThat(Authenticator.getDefault())
                .as("each call replaces the last, so stop() gets back to where it started")
                .isSameAs(hostApplications);
    }

    @Test
    void awaitReadyReturnsOnceTheTunnelIsForwarding() throws Exception {
        App app = boot();

        assertThat(app.awaitReady(java.time.Duration.ofSeconds(10)))
                .as("boot() returns when the tunnel is created; this is when it can carry traffic")
                .isTrue();
        assertThat(app.getSetupFailure()).isNull();
    }

    @Test
    void awaitReadyGivesUpImmediatelyOnATunnelThatFailed() throws Exception {
        App app = embeddedApp();
        app.setupFailed("the tunnel server never came up", 1);

        long started = System.currentTimeMillis();
        assertThat(app.awaitReady(java.time.Duration.ofSeconds(30))).isFalse();

        assertThat(System.currentTimeMillis() - started)
                .as("nothing is still trying, so waiting out the clock only delays the caller")
                .isLessThan(5_000);
        assertThat(app.getSetupFailure()).contains("never came up");
    }

    @Test
    void awaitReadyTimesOutRatherThanBlockingForever() throws Exception {
        App app = embeddedApp();

        assertThat(app.awaitReady(java.time.Duration.ofMillis(200))).isFalse();
    }

    @Test
    void stoppingForgetsTheSocksCredentialsItRegistered() throws Exception {
        // The SOCKS registry is static too, because the JDK's SOCKS client offers no other hook.
        // A stopped tunnel's password went on being offered for the proxy it named -- to the
        // host application's own connections to that same proxy.
        // --proxy-testingbot rather than --proxy, so this exercises the SOCKS registry alone:
        // --proxy-userpwd installs a second, separate authenticator that would answer the probe
        // below whatever the registry says.
        App app = embeddedApp();
        app.setControlProxy("socks5://127.0.0.1:1080");
        app.setControlProxyAuth("socks-user:socks-password");
        ControlPlaneClients.forApp(app).build().close();

        assertThat(socksPasswordFor("127.0.0.1", 1080))
                .as("registered while the tunnel is configured")
                .isNotNull();

        app.stop();

        assertThat(socksPasswordFor("127.0.0.1", 1080))
                .as("and forgotten once it is stopped")
                .isNull();
    }

    /** Asks the JVM's default authenticator what it would give a SOCKS proxy. */
    private static PasswordAuthentication socksPasswordFor(String host, int port) throws Exception {
        return Authenticator.requestPasswordAuthentication(
                host, InetAddress.getByName(host), port, "SOCKS5", "SOCKS authentication",
                null, null, Authenticator.RequestorType.PROXY);
    }

    @Test
    void bootingDoesNotLeaveThreadsBehindAfterStop() throws Exception {
        App app = boot();

        app.stop();

        // Named threads this App owns: the SSH timers, the reconnect scheduler and the self-test
        // retry. A host process is long-lived, so one leaked timer per job accumulates.
        Await.until("the tunnel's own threads to go away", () -> {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                String name = thread.getName();
                if (thread.isAlive() && (name.startsWith("KeepAlive-")
                        || name.startsWith("ConnectionMonitor-")
                        || name.startsWith("PortForwardingMonitor-")
                        || name.startsWith("Reconnect-")
                        || name.startsWith("SelfTestRetry-"))) {
                    return false;
                }
            }
            return true;
        });
    }
}
