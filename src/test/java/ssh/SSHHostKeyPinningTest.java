package ssh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;

import com.testingbot.tunnel.App;
import com.testingbot.tunnel.TestPorts;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Host key pinning against a real SSH server running in this JVM.
 *
 * <p>The negative case is the point of the whole change and is asserted directly: a server whose
 * key is not the pinned one gets no connection, and therefore never receives the account secret
 * that is this session's password. Asserting only the positive case would pass just as happily
 * against a client that accepts every key, which is exactly the state being fixed.
 */
class SSHHostKeyPinningTest {

    private static final String USER = "tunnel-user";
    private static final String PASSWORD = "tunnel-secret";

    private SshServer sshd;
    private SSHTunnel tunnel;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((username, password, session) ->
                USER.equals(username) && PASSWORD.equals(password));
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
    }

    /** The SHA-256 fingerprint of the key this embedded server actually presents. */
    private String serverFingerprint() throws Exception {
        KeyPair pair = sshd.getKeyPairProvider().loadKeys(null).iterator().next();
        ByteArrayBuffer buffer = new ByteArrayBuffer();
        buffer.putRawPublicKey(pair.getPublic());
        return HostKeyPins.displayFingerprint(buffer.getCompactData());
    }

    private App app() throws Exception {
        App app = new App();
        app.setClientKey(USER);
        app.setClientSecret(PASSWORD);
        app.setJettyPort(TestPorts.free());
        app.setSeleniumPort(TestPorts.free());
        return app;
    }

    private SSHTunnel tunnelFor(App app) throws Exception {
        return new SSHTunnel(app, "127.0.0.1", sshd.getPort(), "127.0.0.1");
    }

    @Test
    void connects_whenTheServerPresentsThePinnedKey() throws Exception {
        App app = app();
        app.setSshHostKeyPins(HostKeyPins.parse(serverFingerprint()));

        assertThatCode(() -> tunnel = tunnelFor(app)).doesNotThrowAnyException();
    }

    @Test
    void refusesToConnect_whenTheServerPresentsADifferentKey() throws Exception {
        // A well-formed pin for some other key: the shape of an impersonating server, or of a
        // proxy that answered the CONNECT with an SSH server of its own.
        String wrongPin = HostKeyPins.displayFingerprint(
                "not-this-servers-key".getBytes(StandardCharsets.UTF_8));

        App app = app();
        app.setSshHostKeyPins(HostKeyPins.parse(wrongPin));

        // The constructor connects, so this is the moment the account secret would have gone to
        // the server. No tunnel object comes back at all.
        assertThatThrownBy(() -> tunnelFor(app))
            .hasMessageContaining("Connection failed")
            .hasMessageContaining("HostKey has been changed");
    }

    @Test
    void connectsWithoutVerification_whenNoPinIsConfigured() throws Exception {
        // The default until the service publishes its keys. Asserted so the fallback stays
        // deliberate: this is the behaviour the warning in connect() is about.
        App app = app();
        assertThat(app.getSshHostKeyPins().isEmpty()).isTrue();

        assertThatCode(() -> tunnel = tunnelFor(app)).doesNotThrowAnyException();
    }

}
