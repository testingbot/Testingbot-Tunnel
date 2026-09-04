package com.testingbot.tunnel.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scheme selection and the Basic path, which needs no Kerberos infrastructure. */
class ProxyAuthenticatorTest {

    @Test
    void schemeDefaultsToBasic() {
        // --proxy-userpwd users must keep working untouched.
        assertThat(ProxyAuthenticator.Scheme.parse(null)).isEqualTo(ProxyAuthenticator.Scheme.BASIC);
        assertThat(ProxyAuthenticator.Scheme.parse("")).isEqualTo(ProxyAuthenticator.Scheme.BASIC);
        assertThat(ProxyAuthenticator.Scheme.parse("  ")).isEqualTo(ProxyAuthenticator.Scheme.BASIC);
    }

    @Test
    void schemeParsesCaseInsensitively() {
        assertThat(ProxyAuthenticator.Scheme.parse("basic")).isEqualTo(ProxyAuthenticator.Scheme.BASIC);
        assertThat(ProxyAuthenticator.Scheme.parse(" NEGOTIATE "))
                .isEqualTo(ProxyAuthenticator.Scheme.NEGOTIATE);
    }

    @Test
    void unknownSchemeIsRejected() {
        assertThatThrownBy(() -> ProxyAuthenticator.Scheme.parse("ntlm"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void basic_encodesTheCredentials() {
        ProxyAuthenticator auth = ProxyAuthenticator.basic("user:secret", "proxy.example.com");

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(auth.authorizationValue("proxy.example.com")).isEqualTo(expected);
        assertThat(auth.isNegotiate()).isFalse();
        assertThat(auth.isConfigured()).isTrue();
    }

    @Test
    void noCredentials_producesNoHeader() {
        assertThat(ProxyAuthenticator.none().authorizationValue("proxy.example.com")).isNull();
        assertThat(ProxyAuthenticator.none().isConfigured()).isFalse();
        assertThat(ProxyAuthenticator.basic(null, null).isConfigured()).isFalse();
        assertThat(ProxyAuthenticator.basic("", null).isConfigured()).isFalse();
    }

    @Test
    void create_choosesTheSchemeAndCarriesKerberosSettings() {
        ProxyAuthenticator basic = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.BASIC, "user:secret", null, null, null, null);
        assertThat(basic.isNegotiate()).isFalse();
        assertThat(basic.servicePrincipalFor("proxy.example.com")).isNull();

        ProxyAuthenticator negotiate = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null, null,
                Path.of("/etc/krb5.keytab"), "svc@REALM", null);
        assertThat(negotiate.isNegotiate()).isTrue();
        assertThat(negotiate.isConfigured()).isTrue();
        assertThat(negotiate.servicePrincipalFor("proxy.example.com"))
                .isEqualTo("HTTP@proxy.example.com");
    }

    @Test
    void negotiate_needsNoUserPassword() {
        // Credentials come from the ticket cache or keytab, so --proxy-userpwd is not required
        // and its absence must not disable the scheme.
        ProxyAuthenticator negotiate = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null, null, null, null, null);

        assertThat(negotiate.isConfigured()).isTrue();
    }

    @Test
    void negotiate_returnsNullRatherThanThrowingWhenNoCredentialsExist() {
        // The dial should surface as a 407 from the proxy, not a Kerberos stack trace out of
        // the middle of the connect path. --doctor is where the explanation lives.
        ProxyAuthenticator negotiate = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null, "HTTP/nonexistent.invalid", null, null, null);

        assertThat(negotiate.authorizationValue("nonexistent.invalid")).isNull();
    }

    // ------------------------------------------------------ TB-386 / F10: credential scoping

    /**
     * The credentials were issued for one proxy, and with {@code --pac-local} the PAC file
     * chooses which proxy each destination is dialled through. The argument used to be ignored
     * outright, so the token went to whichever host was being connected to -- base64 of the
     * corporate username and password, in the first packet, before any 407 asked for it.
     */
    @Test
    void basicCredentialsGoOnlyToTheProxyTheyWereIssuedFor() {
        ProxyAuthenticator auth = ProxyAuthenticator.basic("user:secret", "proxy.corp");

        assertThat(auth.authorizationValue("proxy.corp")).isNotNull();
        assertThat(auth.authorizationValue("attacker.example")).isNull();
        assertThat(auth.authorizationValue("proxy.corp.attacker.example")).isNull();
    }

    @Test
    void theProxyHostIsMatchedCaseInsensitively() {
        ProxyAuthenticator auth = ProxyAuthenticator.basic("user:secret", "Proxy.Corp");

        assertThat(auth.authorizationValue("PROXY.CORP")).isNotNull();
        assertThat(auth.authorizationValue("  proxy.corp  ")).isNotNull();
    }

    @Test
    void aNullPeerIsNotTreatedAsAMatch() {
        ProxyAuthenticator auth = ProxyAuthenticator.basic("user:secret", "proxy.corp");

        assertThat(auth.authorizationValue(null)).isNull();
    }

    /** Nothing configured means nothing to give away, so the restriction is irrelevant. */
    @Test
    void anUnconfiguredAuthenticatorIsNotRestricted() {
        assertThat(ProxyAuthenticator.none().isAuthorizedPeer("anywhere.example")).isTrue();
        assertThat(ProxyAuthenticator.none().authorizationValue("anywhere.example")).isNull();
    }
}
