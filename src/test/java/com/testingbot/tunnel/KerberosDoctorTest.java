package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagnostics the ticket makes an acceptance criterion.
 *
 * <p>Every Negotiate misconfiguration otherwise surfaces as the same opaque 407, so what matters
 * is that each one produces a distinct, actionable line -- on a machine with no Kerberos at all,
 * which is what CI and most developer laptops are.
 */
class KerberosDoctorTest {

    private static App app(String scheme, String proxy) {
        App app = new App();
        app.setClientKey("k");
        app.setClientSecret("s");
        if (proxy != null) {
            app.setProxy(proxy);
        }
        app.setProxyAuthScheme(scheme);
        return app;
    }

    private static String messages(List<KerberosDoctor.Finding> findings) {
        return findings.stream().map(KerberosDoctor.Finding::message)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void doesNotRunUnlessNegotiateIsConfigured() {
        assertThat(new KerberosDoctor(app(null, "proxy:8080")).isApplicable()).isFalse();
        assertThat(new KerberosDoctor(app("basic", "proxy:8080")).isApplicable()).isFalse();
        assertThat(new KerberosDoctor(app("negotiate", "proxy:8080")).isApplicable()).isTrue();
    }

    @Test
    void negotiateWithoutAProxy_isReportedAsTheContradictionItIs() {
        List<KerberosDoctor.Finding> findings = new KerberosDoctor(app("negotiate", null)).check();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ok()).isFalse();
        assertThat(findings.get(0).message()).contains("--proxy is not");
    }

    @Test
    void negotiateAgainstSocks5_isReportedAsInapplicable() {
        // SOCKS authenticates inside its own handshake; there is no Proxy-Authorization there.
        List<KerberosDoctor.Finding> findings =
                new KerberosDoctor(app("negotiate", "socks5://proxy:1080")).check();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ok()).isFalse();
        assertThat(findings.get(0).message()).contains("SOCKS5");
    }

    @Test
    void reportsTheServicePrincipalItWillRequest() {
        // Guessing this wrong is the most common field failure, so it must be visible without
        // reading the source.
        List<KerberosDoctor.Finding> findings =
                new KerberosDoctor(app("negotiate", "corp-proxy.example.com:8080")).check();

        assertThat(messages(findings)).contains("HTTP@corp-proxy.example.com");
        assertThat(messages(findings)).contains("--proxy-spn");
    }

    @Test
    void honoursAnExplicitServicePrincipal() {
        App app = app("negotiate", "corp-proxy.example.com:8080");
        app.setProxySpn("HTTP/other.example.com");

        assertThat(messages(new KerberosDoctor(app).check())).contains("HTTP@other.example.com");
    }

    @Test
    void reportsJgssAvailability() {
        assertThat(messages(new KerberosDoctor(app("negotiate", "proxy:8080")).check()))
                .contains("JGSS");
    }

    @Test
    void missingKeytabIsReportedByPath() throws Exception {
        App app = app("negotiate", "proxy:8080");
        app.setKrb5KeyTab("/nonexistent/tunnel.keytab");
        app.setKrb5Principal("svc@REALM");

        List<KerberosDoctor.Finding> findings = new KerberosDoctor(app).check();

        assertThat(findings).anyMatch(f -> !f.ok() && f.message().contains("/nonexistent/tunnel.keytab"));
    }

    @Test
    void readableKeytabIsAccepted() throws Exception {
        Path keyTab = Files.createTempFile("tunnel", ".keytab");
        try {
            App app = app("negotiate", "proxy:8080");
            app.setKrb5KeyTab(keyTab.toString());
            app.setKrb5Principal("svc@REALM");

            assertThat(messages(new KerberosDoctor(app).check()))
                    .contains("Will log in from keytab")
                    .contains("svc@REALM");
        } finally {
            Files.deleteIfExists(keyTab);
        }
    }

    @Test
    void keytabWithoutPrincipalIsReported() throws Exception {
        Path keyTab = Files.createTempFile("tunnel", ".keytab");
        try {
            App app = app("negotiate", "proxy:8080");
            app.setKrb5KeyTab(keyTab.toString());

            List<KerberosDoctor.Finding> findings = new KerberosDoctor(app).check();

            assertThat(findings).anyMatch(f -> !f.ok() && f.message().contains("--krb5-principal"));
        } finally {
            Files.deleteIfExists(keyTab);
        }
    }

    @Test
    void failingServiceTicketIsReportedWithTheReasonAndTheFix() {
        // No KDC on a test machine, so this is the realistic path: it must say which SPN failed
        // and what to do, not just propagate a GSSException.
        List<KerberosDoctor.Finding> findings =
                new KerberosDoctor(app("negotiate", "corp-proxy.example.com:8080")).check();

        assertThat(findings).anyMatch(f -> !f.ok()
                && f.message().contains("HTTP@corp-proxy.example.com")
                && f.message().contains("--proxy-spn"));
    }

    @Test
    void aKrb5HostsFailureDoesNotAdviseProxySpn() {
        // For a --krb5-hosts entry the origin client is built with a null service principal, so
        // the SPN is always derived as HTTP/<host> and --proxy-spn is never read. Naming it here
        // sent the operator to set an option, see no change, and misdiagnose.
        App app = new App();
        app.setNegotiateHosts(
                com.testingbot.tunnel.proxy.NegotiateHosts.parse("intranet.example.com"));

        List<KerberosDoctor.Finding> findings = new KerberosDoctor(app).check();

        assertThat(findings).anyMatch(f -> !f.ok()
                && f.message().contains("HTTP@intranet.example.com")
                && f.message().contains("HTTP/intranet.example.com is registered")
                && !f.message().contains("--proxy-spn overrides"));
    }

    @Test
    void checkNeverThrows_soDoctorAlwaysCompletes() {
        // Doctor's value is reporting every problem at once; an exception here would hide the
        // checks that come after it.
        App app = app("negotiate", "corp-proxy.example.com:8080");
        app.setKrb5KeyTab("/dev/null");
        app.setKrb5Principal("bogus@NOWHERE");

        assertThat(new KerberosDoctor(app).check()).isNotEmpty();
    }
}
