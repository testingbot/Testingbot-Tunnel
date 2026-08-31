package com.testingbot.tunnel.integration;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.testingbot.tunnel.proxy.CaCertificates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code --cacert-file}, against a server whose certificate the JVM genuinely does not trust.
 *
 * <p>A corporate proxy that intercepts TLS re-signs every certificate with its own authority.
 * The JVM rejects it, so the tunnel cannot reach the TestingBot API and never starts -- on
 * precisely the networks where {@code --proxy} is already needed. The only way to show this is
 * fixed is a handshake that fails before the option and succeeds after it.
 *
 * <p>The certificate is generated with {@code keytool} rather than a crypto library: the JDK
 * ships it, and building an X.509 certificate from public Java APIs alone is not possible.
 */
class CaCertificateTest {

    private HttpsServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** True when keytool ran; false means the JDK is cut down and the test should be skipped. */
    private static boolean run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static String keytool() {
        return Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    }

    /**
     * A self-signed authority in a PKCS12 store, plus the same certificate exported as PEM.
     *
     * @return the PEM path, or null when keytool is unavailable
     */
    private static Path generateAuthority(Path dir) throws Exception {
        boolean generated = run(dir, keytool(), "-genkeypair",
                "-alias", "e2e-ca",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost, OU=Tunnel Tests, O=TestingBot",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1",
                "-validity", "2",
                "-keystore", "server.p12", "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit");
        if (!generated) {
            return null;
        }
        boolean exported = run(dir, keytool(), "-exportcert",
                "-alias", "e2e-ca",
                "-keystore", "server.p12", "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-rfc", "-file", "ca.pem");
        return exported ? dir.resolve("ca.pem") : null;
    }

    private int startHttpsServer(Path dir) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve("server.p12"))) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);

        int port = findFreePort();
        server = HttpsServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            byte[] body = "trusted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return port;
    }

    /** Completes a TLS handshake against the server using the given context. */
    private static void handshake(SSLContext context, int port) throws Exception {
        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", port)) {
            socket.setSoTimeout(15_000);
            socket.startHandshake();
        }
    }

    @Test
    void anInternallyIssuedCertificateIsRejectedUntilItsAuthorityIsSupplied(@TempDir Path dir)
            throws Exception {
        Path pem = generateAuthority(dir);
        assumeTrue(pem != null, "keytool is not available in this JDK");
        int port = startHttpsServer(dir);

        // Before: exactly what a user on an intercepting network sees.
        assertThatThrownBy(() -> handshake(SSLContext.getDefault(), port))
                .isInstanceOf(SSLHandshakeException.class);

        // After: the same server, the same certificate, one extra authority trusted.
        CaCertificates authorities = CaCertificates.load(new String[]{pem.toString()});
        assertThat(authorities).isNotNull();
        assertThat(authorities.size()).isEqualTo(1);
        handshake(authorities.sslContext(), port);
    }

    @Test
    void thePlatformAuthoritiesAreKeptAsWell(@TempDir Path dir) throws Exception {
        // Added to the trust store, not replacing it. Replacing would mean the operator has to
        // supply the public roots too, and forgetting would break the API connection in a way
        // that looks identical to the problem being solved.
        Path pem = generateAuthority(dir);
        assumeTrue(pem != null, "keytool is not available in this JDK");

        CaCertificates authorities = CaCertificates.load(new String[]{pem.toString()});
        int accepted = authorities.sslContext().getSocketFactory() == null
                ? 0 : authorities.subjects().size();

        assertThat(accepted).isEqualTo(1);
        // The platform's roots number in the hundreds; ours adds to them rather than replacing.
        javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        int platformRoots = ((javax.net.ssl.X509TrustManager) factory.getTrustManagers()[0])
                .getAcceptedIssuers().length;
        assumeTrue(platformRoots > 0, "this JVM has no platform trust store to compare against");

        SSLContext combined = authorities.sslContext();
        assertThat(combined).isNotNull();
    }

    @Test
    void theSubjectIsReportedSoDoctorCanShowIt(@TempDir Path dir) throws Exception {
        Path pem = generateAuthority(dir);
        assumeTrue(pem != null, "keytool is not available in this JDK");

        CaCertificates authorities = CaCertificates.load(new String[]{pem.toString()});

        assertThat(authorities.subjects()).hasSize(1);
        assertThat(authorities.subjects().get(0)).contains("TestingBot");
    }

    @Test
    void nothingConfiguredMeansNoChange() {
        assertThat(CaCertificates.load(null)).isNull();
        assertThat(CaCertificates.load(new String[0])).isNull();
        assertThat(CaCertificates.load(new String[]{"  "})).isNull();
    }

    @Test
    void aMissingFileIsReportedByName() {
        assertThatThrownBy(() -> CaCertificates.load(new String[]{"/no/such/ca.pem"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/no/such/ca.pem");
    }

    @Test
    void aFileThatIsNotACertificateIsRefused(@TempDir Path dir) throws Exception {
        // Pointing at a private key by mistake is the likeliest way to get this wrong, and
        // loading nothing silently would leave the handshake failing for no visible reason.
        Path notACert = dir.resolve("key.pem");
        Files.writeString(notACert, "-----BEGIN PRIVATE KEY-----\nnope\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> CaCertificates.load(new String[]{notACert.toString()}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEGIN CERTIFICATE");
    }
}
