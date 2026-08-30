package com.testingbot.tunnel;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every flag should also be settable as TESTINGBOT_*, so a container or CI job can be
 * configured entirely from its environment.
 */
class EnvOptionsTest {

    private Options options;

    @BeforeEach
    void setUp() {
        options = new Options();
        options.addOption("P", "se-port", true, "selenium port");
        options.addOption("Y", "proxy", true, "upstream proxy");
        options.addOption("b", "nobump", false, "no ssl bump");
        options.addOption(new Option(null, "config", true, "config file"));
        options.addOption(null, "doctor", false, "doctor");
        Option auth = new Option("a", "auth", true, "basic auth");
        auth.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(auth);
    }

    @Test
    void valueOptions_areReadFromTheEnvironment() {
        String[] expanded = EnvOptions.expand(new String[]{}, options,
                Map.of("TESTINGBOT_SE_PORT", "4446", "TESTINGBOT_PROXY", "corp:8080"));

        assertThat(expanded).containsSequence("--se-port", "4446");
        assertThat(expanded).containsSequence("--proxy", "corp:8080");
    }

    @Test
    void flagOptions_followTheUsualTruthiness() {
        assertThat(EnvOptions.expand(new String[]{}, options, Map.of("TESTINGBOT_NOBUMP", "true")))
                .contains("--nobump");
        assertThat(EnvOptions.expand(new String[]{}, options, Map.of("TESTINGBOT_NOBUMP", "1")))
                .contains("--nobump");
        assertThat(EnvOptions.expand(new String[]{}, options, Map.of("TESTINGBOT_NOBUMP", "false")))
                .doesNotContain("--nobump");
    }

    @Test
    void commandLineWins_overTheEnvironment() {
        String[] expanded = EnvOptions.expand(new String[]{"--se-port", "9999"}, options,
                Map.of("TESTINGBOT_SE_PORT", "4446"));

        assertThat(expanded).containsSequence("--se-port", "9999");
        assertThat(String.join(" ", expanded)).doesNotContain("4446");
    }

    @Test
    void shortFormOnTheCommandLine_alsoSuppressesTheEnvironment() {
        String[] expanded = EnvOptions.expand(new String[]{"-P", "9999"}, options,
                Map.of("TESTINGBOT_SE_PORT", "4446"));

        assertThat(String.join(" ", expanded)).doesNotContain("4446");
    }

    @Test
    void configFileValues_winOverTheEnvironment() {
        // ConfigFile.expand runs first, so its flags are already in args by the time we see them.
        String[] fromConfig = {"--config", "tunnel.conf", "--se-port", "4446"};

        String[] expanded = EnvOptions.expand(fromConfig, options,
                Map.of("TESTINGBOT_SE_PORT", "1234"));

        assertThat(String.join(" ", expanded)).doesNotContain("1234");
    }

    @Test
    void emptyOrBlankValues_areIgnored() {
        assertThat(EnvOptions.expand(new String[]{}, options,
                Map.of("TESTINGBOT_SE_PORT", "   "))).isEmpty();
        assertThat(EnvOptions.expand(new String[]{}, options,
                Map.of("TESTINGBOT_SE_PORT", ""))).isEmpty();
    }

    @Test
    void oneShotAndCredentialOptions_areNeverTakenFromTheEnvironment() {
        // --doctor and --config from the environment would change what a bare invocation does,
        // and TESTINGBOT_AUTH is comma-split into several values elsewhere; expanding it here
        // would hand the parser one malformed value.
        String[] expanded = EnvOptions.expand(new String[]{}, options, Map.of(
                "TESTINGBOT_DOCTOR", "true",
                "TESTINGBOT_CONFIG", "/etc/tunnel.conf",
                "TESTINGBOT_AUTH", "a:80:u:p,b:80:u:p"));

        assertThat(expanded).isEmpty();
    }

    @Test
    void variableName_derivesFromTheLongOption() {
        assertThat(EnvOptions.variableName("se-port")).isEqualTo("TESTINGBOT_SE_PORT");
        assertThat(EnvOptions.variableName("proxy")).isEqualTo("TESTINGBOT_PROXY");
        // The names that already existed must keep working.
        assertThat(EnvOptions.variableName("metrics-auth")).isEqualTo("TESTINGBOT_METRICS_AUTH");
        assertThat(EnvOptions.variableName("proxy-userpwd")).isEqualTo("TESTINGBOT_PROXY_USERPWD");
    }

    @Test
    void unrelatedEnvironmentVariables_areIgnored() {
        String[] expanded = EnvOptions.expand(new String[]{}, options,
                Map.of("PATH", "/usr/bin", "TESTINGBOT_NOT_AN_OPTION", "x", "SE_PORT", "4446"));

        assertThat(expanded).isEmpty();
    }

    @Test
    void expansionIsAppended_soPositionalCredentialsKeepTheirOrder() {
        String[] expanded = EnvOptions.expand(new String[]{"KEY", "SECRET"}, options,
                Map.of("TESTINGBOT_SE_PORT", "4446"));

        assertThat(List.of(expanded).indexOf("KEY")).isZero();
        assertThat(List.of(expanded).indexOf("SECRET")).isEqualTo(1);
    }
}
