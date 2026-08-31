package com.testingbot.tunnel.proxy;

import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ietf.jgss.GSSException;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPNEGO token generation against a real key distribution centre.
 *
 * <p>TB-321 shipped Kerberos support for upstream proxies with no coverage of the exchange
 * itself, because reaching it needed a domain. Apache Kerby runs a KDC in this JVM, so the part
 * that actually matters -- can we obtain a service ticket for the proxy's principal, and what
 * happens when we cannot -- runs in CI.
 *
 * <p>The KDC is started once for the class: it rewrites JVM-global Kerberos configuration, so
 * per-test setup would be both slow and prone to leaking between tests.
 */
class SpnegoKdcTest {

    private static final String REALM = "TESTINGBOT.TEST";
    private static final String PROXY_HOST = "proxy.testingbot.test";
    private static final String SERVICE_PRINCIPAL = "HTTP/" + PROXY_HOST;
    private static final String CLIENT_PRINCIPAL = "tunnel-svc";

    private static SimpleKdcServer kdc;
    private static Path keyTab;
    private static String previousKrb5Conf;
    private static String previousUseSubjectCredsOnly;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeAll
    static void startKdc(@TempDir Path tmp) throws Exception {
        File workDir = tmp.resolve("kdc").toFile();
        assertThat(workDir.mkdirs()).isTrue();

        kdc = new SimpleKdcServer();
        kdc.setKdcRealm(REALM);
        kdc.setKdcHost("127.0.0.1");
        kdc.setKdcTcpPort(freePort());
        kdc.setAllowUdp(false);
        kdc.setWorkDir(workDir);
        kdc.init();
        kdc.start();

        // The client identity the tunnel logs in as, and the proxy's service principal it will
        // ask for a ticket to.
        keyTab = tmp.resolve("tunnel.keytab");
        kdc.createAndExportPrincipals(keyTab.toFile(), CLIENT_PRINCIPAL + "@" + REALM);
        kdc.createPrincipal(SERVICE_PRINCIPAL + "@" + REALM, "servicepass");

        previousKrb5Conf = System.getProperty("java.security.krb5.conf");
        previousUseSubjectCredsOnly = System.getProperty("javax.security.auth.useSubjectCredsOnly");
        System.setProperty("java.security.krb5.conf",
                new File(workDir, "krb5.conf").getAbsolutePath());
        // JGSS otherwise insists on credentials already present in the Subject; the tunnel
        // relies on the login it performs itself.
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
        // No explicit Config.refresh(): sun.security.krb5 is not exported to the unnamed
        // module. SpnegoClient's login module sets refreshKrb5Config, so the file is re-read
        // when it logs in, which is the only path these tests take.
    }

    @AfterAll
    static void stopKdc() throws Exception {
        if (kdc != null) {
            kdc.stop();
        }
        restore("java.security.krb5.conf", previousKrb5Conf);
        restore("javax.security.auth.useSubjectCredsOnly", previousUseSubjectCredsOnly);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /* ------------------------------------------------------------------ happy path */

    @Test
    void obtainsAServiceTicketFromAKeytab() throws Exception {
        SpnegoClient client = new SpnegoClient(SERVICE_PRINCIPAL, keyTab,
                CLIENT_PRINCIPAL + "@" + REALM);

        String token = client.initialToken(PROXY_HOST);

        assertThat(token).isNotBlank();
        byte[] decoded = Base64.getDecoder().decode(token);
        // RFC 2743 application constructed token, then the SPNEGO mechanism OID 1.3.6.1.5.5.2.
        assertThat(decoded[0]).isEqualTo((byte) 0x60);
        assertThat(decoded).contains(new byte[]{0x2b, 0x06, 0x01, 0x05, 0x05, 0x02});
    }

    @Test
    void theDerivedPrincipalWorksWithoutAnExplicitSpn() throws Exception {
        // The default is HTTP/<proxy-host>, which is what a proxy registers. Getting a ticket
        // with it proves the derivation matches what a KDC will actually issue.
        SpnegoClient client = new SpnegoClient(null, keyTab, CLIENT_PRINCIPAL + "@" + REALM);

        assertThat(client.servicePrincipalFor(PROXY_HOST)).isEqualTo("HTTP@" + PROXY_HOST);
        assertThat(client.initialToken(PROXY_HOST)).isNotBlank();
    }

    @Test
    void bothSpnSpellingsReachTheSamePrincipal() throws Exception {
        // Everyone writes SPNs as service/host; JGSS wants service@host.
        String slash = new SpnegoClient("HTTP/" + PROXY_HOST, keyTab,
                CLIENT_PRINCIPAL + "@" + REALM).initialToken(PROXY_HOST);
        String at = new SpnegoClient("HTTP@" + PROXY_HOST, keyTab,
                CLIENT_PRINCIPAL + "@" + REALM).initialToken(PROXY_HOST);

        assertThat(slash).isNotBlank();
        assertThat(at).isNotBlank();
    }

    @Test
    void aFreshTokenIsIssuedEachTime() throws Exception {
        // Tokens carry a timestamp and a sequence; replaying one would be rejected by a proxy.
        SpnegoClient client = new SpnegoClient(SERVICE_PRINCIPAL, keyTab,
                CLIENT_PRINCIPAL + "@" + REALM);

        assertThat(client.initialToken(PROXY_HOST))
                .isNotEqualTo(client.initialToken(PROXY_HOST));
    }

    @Test
    void theAuthenticatorProducesANegotiateHeaderValue() throws Exception {
        // What actually goes on the wire.
        ProxyAuthenticator authenticator = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null, SERVICE_PRINCIPAL, keyTab,
                CLIENT_PRINCIPAL + "@" + REALM);

        String value = authenticator.authorizationValue(PROXY_HOST);

        assertThat(value).startsWith("Negotiate ");
        assertThat(Base64.getDecoder().decode(value.substring("Negotiate ".length())))
                .isNotEmpty();
    }

    /* --------------------------------------------------------------- failure paths */

    @Test
    void anUnknownServicePrincipalIsReportedRatherThanReturningAToken() {
        // The single most common Negotiate failure: the SPN is not registered for the proxy.
        SpnegoClient client = new SpnegoClient("HTTP/not-registered.testingbot.test", keyTab,
                CLIENT_PRINCIPAL + "@" + REALM);

        assertThatThrownBy(() -> client.initialToken("not-registered.testingbot.test"))
                .isInstanceOf(GSSException.class);
    }

    @Test
    void anUnknownClientPrincipalFailsTheLogin() {
        SpnegoClient client = new SpnegoClient(SERVICE_PRINCIPAL, keyTab, "nobody@" + REALM);

        assertThatThrownBy(() -> client.initialToken(PROXY_HOST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kerberos login");
    }

    @Test
    void anUnreadableKeytabIsNamed(@TempDir Path tmp) {
        SpnegoClient client = new SpnegoClient(SERVICE_PRINCIPAL,
                tmp.resolve("absent.keytab"), CLIENT_PRINCIPAL + "@" + REALM);

        assertThatThrownBy(() -> client.initialToken(PROXY_HOST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent.keytab");
    }

    @Test
    void aKeytabWithoutAPrincipalIsRejected() {
        SpnegoClient client = new SpnegoClient(SERVICE_PRINCIPAL, keyTab, null);

        assertThatThrownBy(() -> client.initialToken(PROXY_HOST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void theAuthenticatorSendsNoHeaderWhenATokenCannotBeObtained() {
        // A dial must surface as the proxy's 407, not as a Kerberos stack trace from inside the
        // connect path; --doctor is where the explanation lives.
        ProxyAuthenticator authenticator = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null,
                "HTTP/not-registered.testingbot.test", keyTab, CLIENT_PRINCIPAL + "@" + REALM);

        assertThat(authenticator.authorizationValue("not-registered.testingbot.test")).isNull();
    }

    /* ------------------------------------------------------------------- diagnostics */

    @Test
    void doctorConfirmsAWorkingSetup() {
        com.testingbot.tunnel.App app = new com.testingbot.tunnel.App();
        app.setClientKey("k");
        app.setClientSecret("s");
        app.setProxy(PROXY_HOST + ":8080");
        app.setProxyAuthScheme("negotiate");
        app.setProxySpn(SERVICE_PRINCIPAL);
        app.setKrb5KeyTab(keyTab.toString());
        app.setKrb5Principal(CLIENT_PRINCIPAL + "@" + REALM);

        var findings = new com.testingbot.tunnel.KerberosDoctor(app).check();

        assertThat(findings).allMatch(com.testingbot.tunnel.KerberosDoctor.Finding::ok);
        assertThat(findings).anyMatch(f -> f.message().contains("Obtained a Kerberos service ticket"));
    }

    @Test
    void doctorExplainsAnUnregisteredServicePrincipal() {
        // The failure a customer actually hits, and the reason --doctor exists for this feature.
        com.testingbot.tunnel.App app = new com.testingbot.tunnel.App();
        app.setClientKey("k");
        app.setClientSecret("s");
        app.setProxy("not-registered.testingbot.test:8080");
        app.setProxyAuthScheme("negotiate");
        app.setKrb5KeyTab(keyTab.toString());
        app.setKrb5Principal(CLIENT_PRINCIPAL + "@" + REALM);

        var findings = new com.testingbot.tunnel.KerberosDoctor(app).check();

        assertThat(findings).anyMatch(f -> !f.ok()
                && f.message().contains("Could not obtain a service ticket")
                && f.message().contains("--proxy-spn"));
    }
}
