package ssh;

import com.testingbot.tunnel.App;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSH connection traversing a SOCKS5 upstream proxy.
 *
 * <p>{@code --proxy socks5://...} takes a different branch from the HTTP one -- JSch's own
 * ProxySOCKS5 rather than our CONNECT implementation -- and that branch had no coverage. A
 * minimal SOCKS5 server here speaks enough of RFC 1928 to carry a full SSH session.
 */
class SshThroughSocksProxyTest {

    private static final String USER = "tunnel-user";
    private static final String PASSWORD = "tunnel-secret";

    private SshServer sshd;
    private ServerSocket socks;
    private ExecutorService pool;
    private SSHTunnel tunnel;
    private final List<String> negotiations = new CopyOnWriteArrayList<>();

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
        if (socks != null && !socks.isClosed()) {
            socks.close();
        }
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Enough of RFC 1928 to be useful: greeting, optional username/password sub-negotiation
     * (RFC 1929), CONNECT to an IPv4 or domain address, then a byte splice.
     */
    private void startSocks(String requiredUser, String requiredPassword) throws IOException {
        socks = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(() -> {
            while (!socks.isClosed()) {
                try {
                    Socket client = socks.accept();
                    pool.submit(() -> serve(client, requiredUser, requiredPassword));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    private void serve(Socket client, String requiredUser, String requiredPassword) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            in.readByte();                                   // version
            int methodCount = in.readUnsignedByte();
            byte[] methods = new byte[methodCount];
            in.readFully(methods);

            boolean wantsAuth = requiredUser != null;
            out.write(new byte[]{0x05, (byte) (wantsAuth ? 0x02 : 0x00)});
            out.flush();

            if (wantsAuth) {
                in.readByte();                               // sub-negotiation version
                byte[] user = new byte[in.readUnsignedByte()];
                in.readFully(user);
                byte[] pass = new byte[in.readUnsignedByte()];
                in.readFully(pass);
                String suppliedUser = new String(user, StandardCharsets.UTF_8);
                String suppliedPass = new String(pass, StandardCharsets.UTF_8);
                negotiations.add(suppliedUser + ":" + suppliedPass);
                boolean ok = requiredUser.equals(suppliedUser)
                        && requiredPassword.equals(suppliedPass);
                out.write(new byte[]{0x01, (byte) (ok ? 0x00 : 0x01)});
                out.flush();
                if (!ok) {
                    client.close();
                    return;
                }
            }

            in.readByte();                                   // version
            in.readByte();                                   // command (CONNECT)
            in.readByte();                                   // reserved
            int addressType = in.readUnsignedByte();
            String host;
            if (addressType == 0x01) {
                byte[] address = new byte[4];
                in.readFully(address);
                host = InetAddress.getByAddress(address).getHostAddress();
            } else {
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                host = new String(name, StandardCharsets.UTF_8);
            }
            int port = in.readUnsignedShort();
            negotiations.add("CONNECT " + host + ":" + port);

            Socket upstream = new Socket(host, port);
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();

            InputStream fromUpstream = upstream.getInputStream();
            OutputStream toClient = client.getOutputStream();
            pool.submit(() -> copy(fromUpstream, toClient));
            copy(client.getInputStream(), upstream.getOutputStream());
        } catch (Exception ignored) {
            // the assertions cover the outcome
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
    void theSshConnectionIsMadeThroughASocks5Proxy() throws Exception {
        startSocks(null, null);
        App app = app();
        app.setProxy("socks5://127.0.0.1:" + socks.getLocalPort());

        tunnel = new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");

        assertThat(tunnel.isAuthenticated()).isTrue();
        assertThat(negotiations).anyMatch(n -> n.startsWith("CONNECT 127.0.0.1:" + sshd.getPort()));
    }

    @Test
    void socksCredentialsAreNegotiatedWhenTheProxyDemandsThem() throws Exception {
        startSocks("socksuser", "sockspass");
        App app = app();
        app.setProxy("socks5://127.0.0.1:" + socks.getLocalPort());
        app.setProxyAuth("socksuser:sockspass");

        tunnel = new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");

        assertThat(tunnel.isAuthenticated()).isTrue();
        assertThat(negotiations).contains("socksuser:sockspass");
    }

    @Test
    void wrongSocksCredentialsFailTheConnection() throws Exception {
        startSocks("socksuser", "sockspass");
        App app = app();
        app.setProxy("socks5://127.0.0.1:" + socks.getLocalPort());
        app.setProxyAuth("socksuser:wrong");

        assertThatThrownBy(() -> new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Connection failed");
    }

    @Test
    void credentialSplittingToleratesAColonInThePassword() {
        // Passwords routinely contain colons; splitting on every one would truncate them.
        assertThat(SSHTunnel.splitCredentials("user:pass")).containsExactly("user", "pass");
        assertThat(SSHTunnel.splitCredentials("user:pa:ss")).containsExactly("user", "pa:ss");
        assertThat(SSHTunnel.splitCredentials("user:")).containsExactly("user", "");
    }

    @Test
    void credentialSplittingReturnsNullWhenThereIsNothingUsable() {
        assertThat(SSHTunnel.splitCredentials(null)).isNull();
        assertThat(SSHTunnel.splitCredentials("")).isNull();
        // No colon at all is not a user:password pair, and guessing would send the whole
        // string as a username.
        assertThat(SSHTunnel.splitCredentials("nocolon")).isNull();
    }
}
