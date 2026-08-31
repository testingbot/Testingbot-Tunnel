package com.testingbot.tunnel;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --proxy-testingbot}: a separate upstream proxy for reaching TestingBot itself.
 *
 * <p>Once egress is filtered by destination, the proxy allowed to reach the public internet is
 * often not the one that reaches internal test targets. Before this the tunnel had one
 * {@code --proxy} for both, so those networks had no working configuration.
 *
 * <p>The two properties worth pinning down are the fallback -- the common case of one proxy for
 * everything must keep working untouched -- and that credentials do not follow the split.
 */
class ControlProxyTest {

    private static CommandLine parse(String... args) throws ParseException {
        Options options = App.buildOptions();
        return new PosixParser().parse(options, args);
    }

    private static App configured(String... args) throws Exception {
        App app = new App();
        App.applyOptions(app, parse(args));
        return app;
    }

    @Test
    void controlTrafficUsesTheOrdinaryProxyWhenNoneIsGiven() throws Exception {
        // The overwhelmingly common case. Adding the option must not change it.
        App app = configured("--proxy", "proxy.example:8080",
                "--proxy-userpwd", "user:secret");

        assertThat(app.getControlProxy()).isEqualTo("proxy.example:8080");
        assertThat(app.getControlProxyAuth()).isEqualTo("user:secret");
        assertThat(app.hasSeparateControlProxy()).isFalse();
    }

    @Test
    void nothingConfiguredMeansNoProxyAnywhere() throws Exception {
        App app = configured();

        assertThat(app.getControlProxy()).isNull();
        assertThat(app.getControlProxyAuth()).isNull();
    }

    @Test
    void aSeparateControlProxyIsUsedOnlyForControlTraffic() throws Exception {
        App app = configured("--proxy", "test-traffic.example:8080",
                "--proxy-testingbot", "control.example:3128");

        assertThat(app.getProxy())
                .as("test traffic keeps the proxy it was given")
                .isEqualTo("test-traffic.example:8080");
        assertThat(app.getControlProxy()).isEqualTo("control.example:3128");
        assertThat(app.hasSeparateControlProxy()).isTrue();
    }

    @Test
    void credentialsDoNotFollowToADifferentProxy() throws Exception {
        // --proxy-userpwd was issued for one proxy operator. Sending it to another because a
        // second proxy happens to be configured would hand over credentials that are not theirs.
        App app = configured("--proxy", "test-traffic.example:8080",
                "--proxy-userpwd", "user:secret",
                "--proxy-testingbot", "control.example:3128");

        assertThat(app.getControlProxyAuth())
                .as("the control proxy was given no credentials of its own")
                .isNull();
        assertThat(app.getProxyAuth()).isEqualTo("user:secret");
    }

    @Test
    void eachProxyCanHaveItsOwnCredentials() throws Exception {
        App app = configured("--proxy", "test-traffic.example:8080",
                "--proxy-userpwd", "test-user:test-pass",
                "--proxy-testingbot", "control.example:3128",
                "--proxy-testingbot-userpwd", "control-user:control-pass");

        assertThat(app.getProxyAuth()).isEqualTo("test-user:test-pass");
        assertThat(app.getControlProxyAuth()).isEqualTo("control-user:control-pass");
    }

    @Test
    void aControlProxyCanBeUsedWithNoOrdinaryProxy() throws Exception {
        // Reaching TestingBot through a proxy while test targets are dialled directly.
        App app = configured("--proxy-testingbot", "control.example:3128");

        assertThat(app.getProxy()).isNull();
        assertThat(app.getControlProxy()).isEqualTo("control.example:3128");
    }

    @Test
    void theSchemeFormsAreAcceptedTheSameAsForProxy() throws Exception {
        assertThat(configured("--proxy-testingbot", "socks5://control.example:1080")
                .getControlProxy()).isEqualTo("socks5://control.example:1080");
        assertThat(configured("--proxy-testingbot", "http://control.example:3128")
                .getControlProxy()).isEqualTo("http://control.example:3128");
    }

    @Test
    void anUnparseableValueIsRefusedWithItsOwnName() throws Exception {
        // Naming the option matters: with two proxy flags, "invalid proxy" would not say which.
        assertThatThrownBy(() -> configured("--proxy-testingbot", "ftp://control.example:3128"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--proxy-testingbot");
    }

    @Test
    void aBareHostnameIsAcceptedJustAsItIsForProxy() throws Exception {
        // ProxySpec takes a bare hostname on purpose: rejecting it made "--proxy corp-proxy"
        // silently dial origins directly, bypassing a proxy the user believed was in force.
        // Being stricter here than --proxy would be an inconsistency, not an improvement.
        assertThat(configured("--proxy-testingbot", "corp-proxy").getControlProxy())
                .isEqualTo("corp-proxy");
    }

    @Test
    void credentialsWithoutAColonAreRefused() {
        assertThatThrownBy(() -> configured("--proxy-testingbot", "control.example:3128",
                "--proxy-testingbot-userpwd", "no-colon"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("user:password");
    }

    @Test
    void theControlAuthenticatorUsesTheControlCredentials() throws Exception {
        App app = configured("--proxy", "test-traffic.example:8080",
                "--proxy-userpwd", "test-user:test-pass",
                "--proxy-testingbot", "control.example:3128",
                "--proxy-testingbot-userpwd", "control-user:control-pass");

        String controlValue = app.controlProxyAuthenticator()
                .authorizationValue("control.example");
        String testValue = app.proxyAuthenticator().authorizationValue("test-traffic.example");

        assertThat(controlValue).isNotEqualTo(testValue);
        assertThat(new String(java.util.Base64.getDecoder().decode(
                controlValue.substring("Basic ".length()))))
                .isEqualTo("control-user:control-pass");
    }

    @Test
    void theControlAuthenticatorIsTheOrdinaryOneWhenNotSplit() throws Exception {
        App app = configured("--proxy", "proxy.example:8080",
                "--proxy-userpwd", "user:secret");

        assertThat(app.controlProxyAuthenticator().authorizationValue("proxy.example"))
                .isEqualTo(app.proxyAuthenticator().authorizationValue("proxy.example"));
    }
}
