package com.testingbot.tunnel;

import com.testingbot.tunnel.pac.PacPolicy;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Option handling and its validation messages.
 *
 * <p>These are the paths a user hits when they get a flag slightly wrong, so the message is the
 * feature. They were previously reachable only by starting the process, which meant the
 * validation added for --proxy-auth-scheme, --header, --pac-local and friends had no coverage
 * at all.
 */
class OptionHandlingTest {

    /** The real option set, so a test cannot drift from what the tunnel actually accepts. */
    private static CommandLine parse(String... args) throws ParseException {
        Options options = App.buildOptions();
        return new PosixParser().parse(options, args);
    }

    private static App configured(String... args) throws ParseException {
        App app = new App();
        App.applyUpstreamProxyOptions(app, parse(args));
        return app;
    }

    /* ------------------------------------------------------------------- --proxy */

    @Test
    void proxyIsApplied() throws Exception {
        assertThat(configured("--proxy", "corp:8080").getProxy()).isEqualTo("corp:8080");
    }

    @Test
    void invalidProxyIsRejectedWithTheExpectedForms() {
        assertThatThrownBy(() -> configured("--proxy", "ftp://x:1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid --proxy value")
                .hasMessageContaining("socks5://");
    }

    /* ------------------------------------------------------ --proxy-auth-scheme */

    @Test
    void proxyAuthSchemeAcceptsBothSchemes() throws Exception {
        assertThat(configured("--proxy-auth-scheme", "basic").getProxyAuthScheme())
                .isEqualTo("basic");
        assertThat(configured("--proxy-auth-scheme", "negotiate").getProxyAuthScheme())
                .isEqualTo("negotiate");
    }

    @Test
    void unknownProxyAuthSchemeNamesTheValidOnes() {
        assertThatThrownBy(() -> configured("--proxy-auth-scheme", "ntlm"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("ntlm")
                .hasMessageContaining("basic or negotiate");
    }

    @Test
    void keytabWithoutPrincipalIsRejected() {
        // The pair is useless apart, and the failure would otherwise appear only at dial time.
        assertThatThrownBy(() -> configured("--krb5-keytab", "/etc/krb5.keytab"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--krb5-principal");
    }

    @Test
    void keytabWithPrincipalIsAccepted() throws Exception {
        App app = configured("--krb5-keytab", "/etc/krb5.keytab",
                "--krb5-principal", "svc@REALM", "--proxy-spn", "HTTP/p.corp");

        assertThat(app.getKrb5KeyTab()).isEqualTo("/etc/krb5.keytab");
        assertThat(app.getKrb5Principal()).isEqualTo("svc@REALM");
        assertThat(app.getProxySpn()).isEqualTo("HTTP/p.corp");
    }

    @Test
    void proxyAuthenticatorReflectsTheConfiguredScheme() throws Exception {
        assertThat(configured("--proxy", "corp:8080", "--proxy-auth-scheme", "negotiate")
                .proxyAuthenticator().isNegotiate()).isTrue();
        assertThat(configured("--proxy", "corp:8080").proxyAuthenticator().isNegotiate()).isFalse();
    }

    /* --------------------------------------------------------------- --pac-local */

    @Test
    void pacLocalIsLoadedLazilyAndCached(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("p.pac");
        Files.writeString(file, "function FindProxyForURL(url, host) { return 'DIRECT'; }");

        App app = configured("--pac-local", file.toString());

        assertThat(app.getPacLocal()).isEqualTo(file.toString());
        PacPolicy first = app.getPacPolicy();
        assertThat(first).isNotNull();
        assertThat(app.getPacPolicy()).isSameAs(first);
    }

    @Test
    void noPacLocalMeansNoPolicy() throws Exception {
        assertThat(configured().getPacPolicy()).isNull();
    }

    @Test
    void settingPacLocalAgainDiscardsTheLoadedPolicy(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.pac");
        Files.writeString(a, "function FindProxyForURL(url, host) { return 'DIRECT'; }");
        Path b = tmp.resolve("b.pac");
        Files.writeString(b, "function FindProxyForURL(url, host) { return 'PROXY p:1'; }");

        App app = new App();
        app.setPacLocal(a.toString());
        assertThat(app.getPacPolicy().resolve("http://x/", "x").isDirect()).isTrue();

        app.setPacLocal(b.toString());
        assertThat(app.getPacPolicy().resolve("http://x/", "x").isDirect()).isFalse();
    }

    /* ------------------------------------------------------------------ --pac-test */

    @Test
    void pacTestReportsSuccessAndFailureByExitCode(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("p.pac");
        Files.writeString(file, """
                function FindProxyForURL(url, host) {
                    if (dnsDomainIs(host, ".corp")) return "DIRECT";
                    return "PROXY p.corp:8080";
                }
                """);

        assertThat(App.pacTest(file.toString(), "http://a.corp/x")).isZero();
        assertThat(App.pacTest(file.toString(), "https://elsewhere.com/")).isZero();
        // A URL with no host cannot be evaluated.
        assertThat(App.pacTest(file.toString(), "not-a-url")).isEqualTo(1);
        assertThat(App.pacTest(tmp.resolve("absent.pac").toString(), "http://x/")).isEqualTo(1);
    }

    /* ------------------------------------------------------------------- --header */

    @Test
    void headerRulesAreValidatedAtParseTime() {
        assertThatCode(() -> App.validateHeaderRules("--header",
                new String[]{"X-A: 1", "-X-B", "-X-C*", "X-D;"})).doesNotThrowAnyException();

        assertThatThrownBy(() -> App.validateHeaderRules("--header", new String[]{"no separator"}))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--header");

        // CRLF would let a rule inject further headers into every request.
        assertThatThrownBy(() -> App.validateHeaderRules("--response-header",
                new String[]{"X-A: a\r\nX-Evil: b"}))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--response-header");
    }

    /* --------------------------------------------------------------- --metrics-port */

    @Test
    void readinessPortFallsBackToTheDefault() throws Exception {
        assertThat(App.readinessPort(parse())).isEqualTo(App.DEFAULT_METRICS_PORT);
        assertThat(App.readinessPort(parse("--metrics-port", "9111"))).isEqualTo(9111);
        assertThat(App.readinessPort(parse("--metrics-port", "  9222 "))).isEqualTo(9222);
    }

    @Test
    void aNonNumericMetricsPortIsRejected() {
        assertThatThrownBy(() -> App.readinessPort(parse("--metrics-port", "http")))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Invalid --metrics-port");
    }

    /* ------------------------------------------------------------------- runtime */

    @Test
    void javaVersionCheckPassesOnTheRuntimeRunningTheseTests() {
        // The suite requires 17+, so this must hold; the interesting half is that the constant
        // tracks maven.compiler.release rather than the old 11.
        assertThat(App.checkJavaVersion()).isTrue();
    }
}
