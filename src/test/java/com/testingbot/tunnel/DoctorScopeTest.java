package com.testingbot.tunnel;

import com.testingbot.tunnel.proxy.NegotiateHosts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --doctor} must diagnose the configuration the user actually gave it.
 *
 * <p>Two ways it did not: it overwrote a configured {@code --localproxy} with a random free
 * port, so the one case the port check exists for was never tested; and it ran the Kerberos
 * environment checks only when the proxy used Negotiate, so a {@code --krb5-hosts}-only setup —
 * which {@code isApplicable()} deliberately admits — never saw "no keytab and no ticket cache".
 */
class DoctorScopeTest {

    private static App configured() {
        App app = new App();
        app.setClientKey("test_key");
        app.setClientSecret("test_secret");
        return app;
    }

    @Test
    void aConfiguredLocalproxyPortIsTheOneChecked() {
        App app = configured();
        app.setJettyPort(9999);

        // Doctor's constructor runs the checks; only the port selection matters here.
        new Doctor(app);

        assertThat(app.getJettyPort())
                .as("overwriting it meant --doctor reported on a port the user never chose")
                .isEqualTo(9999);
    }

    @Test
    void withoutOneAFreePortIsStillChosen() {
        App app = configured();

        new Doctor(app);

        assertThat(app.getJettyPort()).isGreaterThan(0);
    }

    @Test
    void theKerberosEnvironmentIsCheckedForAKrb5HostsOnlyConfig() {
        // No --proxy and no --proxy-auth-scheme: Kerberos is configured for sites only.
        App app = configured();
        app.setNegotiateHosts(NegotiateHosts.parse("intranet.example"));

        KerberosDoctor kerberos = new KerberosDoctor(app);
        assertThat(kerberos.isApplicable()).isTrue();

        List<String> messages = kerberos.check().stream()
                .map(KerberosDoctor.Finding::message).toList();

        assertThat(messages)
                .as("JGSS availability does not depend on there being a proxy")
                .anyMatch(m -> m.contains("JGSS"));
        assertThat(messages)
                .as("the operator has to be told there is no keytab and no ticket cache")
                .anyMatch(m -> m.toLowerCase().contains("ticket cache")
                        || m.toLowerCase().contains("keytab"));
    }

    @Test
    void theEnvironmentChecksAreNotRunTwiceOnTheProxyPath() {
        // They moved above an early return; running them again below would double every line.
        App app = configured();
        app.setProxy("proxy.example:8080");
        app.setProxyAuthScheme("negotiate");

        List<String> messages = new KerberosDoctor(app).check().stream()
                .map(KerberosDoctor.Finding::message).toList();

        assertThat(messages.stream().filter(m -> m.contains("JGSS")).count()).isEqualTo(1);
    }

    @Test
    void nothingKerberosIsReportedWhenNothingIsConfigured() {
        assertThat(new KerberosDoctor(configured()).isApplicable()).isFalse();
    }
}
