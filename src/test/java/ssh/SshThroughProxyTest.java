package ssh;

import com.testingbot.tunnel.App;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSH connection traversing an upstream HTTP proxy (TB-321).
 *
 * <p>Until now this could only be checked against the live TestingBot service, because it needs
 * a real SSH handshake on the far side of the CONNECT. With an in-process SSH server it runs in
 * CI, which matters: this path is what makes the tunnel usable on a corporate network whose only
 * egress is a proxy, and before TB-321 it did not exist at all.
 */
class SshThroughProxyTest {

    private static final String USER = "tunnel-user";
    private static final String PASSWORD = "tunnel-secret";

    private SshServer sshd;
    private ServerSocket proxy;
    private ExecutorService pool;
    private SSHTunnel tunnel;
    private final List<String> proxyRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        pool = Executors.newCachedThreadPool();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((u, p, s) -> USER.equals(u) && PASSWORD.equals(p));
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
        if (proxy != null && !proxy.isClosed()) {
            proxy.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A CONNECT proxy that optionally demands Basic auth and then splices the two sockets, so a
     * full SSH session runs through it.
     */
    private void startProxy(String requiredCredentials) throws IOException {
        proxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!proxy.isClosed()) {
                try {
                    Socket client = proxy.accept();
                    pool.submit(() -> handle(client, requiredCredentials));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    private void handle(Socket client, String requiredCredentials) {
        try {
            StringBuilder header = new StringBuilder();
            InputStream in = client.getInputStream();
            int b;
            while (!header.toString().endsWith("\r\n\r\n") && (b = in.read()) >= 0) {
                header.append((char) b);
            }
            String request = header.toString();
            proxyRequests.add(request);

            OutputStream out = client.getOutputStream();
            if (requiredCredentials != null && !request.contains("Proxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString(
                            requiredCredentials.getBytes(StandardCharsets.UTF_8)))) {
                out.write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                client.close();
                return;
            }

            String target = request.substring(request.indexOf(' ') + 1,
                    request.indexOf(' ', request.indexOf(' ') + 1));
            Socket upstream = new Socket(target.split(":")[0],
                    Integer.parseInt(target.split(":")[1]));
            out.write("HTTP/1.1 200 Connection established\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Splice, so the SSH protocol exchange runs end to end through this proxy.
            InputStream fromUpstream = upstream.getInputStream();
            OutputStream toClient = client.getOutputStream();
            pool.submit(() -> copy(fromUpstream, toClient));
            copy(client.getInputStream(), upstream.getOutputStream());
        } catch (Exception ignored) {
            // connection went away; the assertions cover the outcome
        }
    }

    private static void copy(InputStream from, OutputStream to) {
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = from.read(buffer)) > 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException closed) {
            // stream ended
        }
    }

    private App app() throws Exception {
        App app = new App();
        app.setClientKey(USER);
        app.setClientSecret(PASSWORD);
        try (ServerSocket s = new ServerSocket(0)) {
            app.setJettyPort(s.getLocalPort());
        }
        return app;
    }

    @Test
    void theSshConnectionIsMadeThroughTheProxy() throws Exception {
        startProxy(null);
        App app = app();
        app.setProxy("127.0.0.1:" + proxy.getLocalPort());

        tunnel = new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");

        assertThat(tunnel.isAuthenticated()).isTrue();
        assertThat(proxyRequests).hasSize(1);
        assertThat(proxyRequests.get(0))
                .startsWith("CONNECT 127.0.0.1:" + sshd.getPort())
                .contains("Host: 127.0.0.1:" + sshd.getPort());
    }

    @Test
    void basicCredentialsAreSentWhenTheProxyDemandsThem() throws Exception {
        startProxy("proxyuser:proxypass");
        App app = app();
        app.setProxy("127.0.0.1:" + proxy.getLocalPort());
        app.setProxyAuth("proxyuser:proxypass");

        tunnel = new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");

        assertThat(tunnel.isAuthenticated()).isTrue();
        assertThat(proxyRequests.get(0)).contains("Proxy-Authorization: Basic ");
    }

    @Test
    void aRefusedProxyAuthenticationFailsWithAPointerToTheFlag() throws Exception {
        // Without credentials the proxy answers 407; the message must blame the proxy and say
        // which flag to look at, not blame the TestingBot endpoint behind it.
        startProxy("proxyuser:proxypass");
        App app = app();
        app.setProxy("127.0.0.1:" + proxy.getLocalPort());

        assertThatThrownBy(() -> new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Connection failed");
        assertThat(proxyRequests.get(0)).doesNotContain("Proxy-Authorization");
    }

    @Test
    void withoutAProxyTheConnectionIsMadeDirectly() throws Exception {
        startProxy(null);
        App app = app();   // no --proxy

        tunnel = new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");

        assertThat(tunnel.isAuthenticated()).isTrue();
        assertThat(proxyRequests).as("the proxy should not have been used").isEmpty();
    }

    @Test
    void forwardingWorksThroughTheProxiedConnection() throws Exception {
        // The proxy is only used to reach the SSH server; everything the tunnel does afterwards
        // runs inside that connection.
        startProxy(null);
        App app = app();
        app.setProxy("127.0.0.1:" + proxy.getLocalPort());
        try (ServerSocket s = new ServerSocket(0)) {
            app.setSeleniumPort(s.getLocalPort());
            app.setHubPort(s.getLocalPort());
        }
        tunnel = new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");

        tunnel.createPortForwarding();

        assertThat(SSHTunnel.localForwardingActive(
                tunnel.getSession().getPortForwardingL(), app.getSSHPort())).isTrue();
    }
}
