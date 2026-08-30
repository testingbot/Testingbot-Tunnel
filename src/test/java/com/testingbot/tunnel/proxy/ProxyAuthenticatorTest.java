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
        ProxyAuthenticator auth = ProxyAuthenticator.basic("user:secret");

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
        assertThat(ProxyAuthenticator.basic(null).isConfigured()).isFalse();
        assertThat(ProxyAuthenticator.basic("").isConfigured()).isFalse();
    }

    @Test
    void create_choosesTheSchemeAndCarriesKerberosSettings() {
        ProxyAuthenticator basic = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.BASIC, "user:secret", null, null, null);
        assertThat(basic.isNegotiate()).isFalse();
        assertThat(basic.servicePrincipalFor("proxy.example.com")).isNull();

        ProxyAuthenticator negotiate = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null, null,
                Path.of("/etc/krb5.keytab"), "svc@REALM");
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
                ProxyAuthenticator.Scheme.NEGOTIATE, null, null, null, null);

        assertThat(negotiate.isConfigured()).isTrue();
    }

    @Test
    void negotiate_returnsNullRatherThanThrowingWhenNoCredentialsExist() {
        // The dial should surface as a 407 from the proxy, not a Kerberos stack trace out of
        // the middle of the connect path. --doctor is where the explanation lives.
        ProxyAuthenticator negotiate = ProxyAuthenticator.create(
                ProxyAuthenticator.Scheme.NEGOTIATE, null, "HTTP/nonexistent.invalid", null, null);

        assertThat(negotiate.authorizationValue("nonexistent.invalid")).isNull();
    }
}
