package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service principal derivation.
 *
 * <p>Guessing the SPN wrong is one of the most common ways Negotiate fails in the field, so the
 * derivation is worth pinning even though the GSS exchange itself needs a KDC to exercise.
 */
class SpnegoClientTest {

    @Test
    void derivesHttpSpnFromTheProxyHost() {
        SpnegoClient client = new SpnegoClient(null, null, null);

        assertThat(client.servicePrincipalFor("proxy.corp.example")).isEqualTo("HTTP@proxy.corp.example");
    }

    @Test
    void lowercasesTheDerivedHost() {
        // Kerberos host names are conventionally lower case; a mixed-case --proxy should not
        // change which principal is requested.
        SpnegoClient client = new SpnegoClient(null, null, null);

        assertThat(client.servicePrincipalFor("Proxy.CORP.Example")).isEqualTo("HTTP@proxy.corp.example");
    }

    @Test
    void explicitPrincipalOverridesTheDerivedOne() {
        SpnegoClient client = new SpnegoClient("HTTP/other.corp.example", null, null);

        assertThat(client.servicePrincipalFor("proxy.corp.example"))
                .isEqualTo("HTTP@other.corp.example");
    }

    @Test
    void acceptsBothSpnSpellings() {
        // Everyone writes SPNs as service/host; JGSS wants service@host for a host-based name.
        assertThat(SpnegoClient.normalise("HTTP/proxy.example")).isEqualTo("HTTP@proxy.example");
        assertThat(SpnegoClient.normalise("HTTP@proxy.example")).isEqualTo("HTTP@proxy.example");
    }

    @Test
    void normaliseLeavesOtherFormsAlone() {
        assertThat(SpnegoClient.normalise("HTTP")).isEqualTo("HTTP");
        assertThat(SpnegoClient.normalise("/leading")).isEqualTo("/leading");
    }

    @Test
    void blankOverrideFallsBackToDerivation() {
        assertThat(new SpnegoClient("   ", null, null).servicePrincipalFor("proxy.example"))
                .isEqualTo("HTTP@proxy.example");
    }
}
