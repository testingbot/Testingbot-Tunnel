package com.testingbot.tunnel;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigFileTest {

    @TempDir
    Path tmp;

    private Options options;

    @BeforeEach
    void setUp() {
        options = new Options();
        options.addOption("P", "se-port", true, "selenium port");
        options.addOption("j", "localproxy", true, "proxy port");
        options.addOption("Y", "proxy", true, "upstream proxy");
        options.addOption("b", "nobump", false, "no ssl bump");
        options.addOption("q", "nocache", false, "no cache");
        Option config = new Option(null, "config", true, "config file");
        options.addOption(config);
    }

    private Path write(String contents) throws Exception {
        Path file = tmp.resolve("tunnel.conf");
        Files.writeString(file, contents);
        return file;
    }

    @Test
    void valueSettings_becomeFlags() throws Exception {
        Path file = write("se-port = 4446\nlocalproxy = 8899\n");

        String[] expanded = ConfigFile.expand(new String[]{"--config", file.toString()}, options);

        assertThat(expanded).contains("--se-port", "4446", "--localproxy", "8899");
    }

    @Test
    void booleanSettings_becomeBareFlags() throws Exception {
        Path file = write("nobump = true\nnocache = false\n");

        String[] expanded = ConfigFile.expand(new String[]{"--config", file.toString()}, options);

        assertThat(expanded).contains("--nobump");
        assertThat(expanded).doesNotContain("--nocache");
    }

    @Test
    void commandLineWins_overConfigFile() throws Exception {
        Path file = write("se-port = 4446\n");

        String[] expanded = ConfigFile.expand(
                new String[]{"--config", file.toString(), "--se-port", "9999"}, options);

        // The config value must not be appended at all, or the parser could pick either.
        assertThat(expanded).containsSequence("--se-port", "9999");
        assertThat(String.join(" ", expanded)).doesNotContain("4446");
    }

    @Test
    void credentials_areAppendedAsPositionalArguments() throws Exception {
        Path file = write("client-key = KEY123\nclient-secret = SECRET456\nse-port = 4446\n");

        String[] expanded = ConfigFile.expand(new String[]{"--config", file.toString()}, options);

        // Positional args must come last and in key-then-secret order.
        assertThat(expanded[expanded.length - 2]).isEqualTo("KEY123");
        assertThat(expanded[expanded.length - 1]).isEqualTo("SECRET456");
    }

    @Test
    void unknownKey_isRejectedWithTheKeyName() throws Exception {
        Path file = write("not-a-real-option = 1\n");

        assertThatThrownBy(() -> ConfigFile.expand(new String[]{"--config", file.toString()}, options))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("not-a-real-option");
    }

    @Test
    void missingFile_isReportedClearly() {
        assertThatThrownBy(() ->
                ConfigFile.expand(new String[]{"--config", tmp.resolve("nope.conf").toString()}, options))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void withoutConfigFlag_argumentsPassThroughUnchanged() throws Exception {
        String[] args = {"--se-port", "4445", "KEY", "SECRET"};
        assertThat(ConfigFile.expand(args, options)).isSameAs(args);
    }

    @Test
    void commentsAndBlankLines_areIgnored() throws Exception {
        Path file = write("# a comment\n\n  \nse-port = 4446\n! another comment\n");

        String[] expanded = ConfigFile.expand(new String[]{"--config", file.toString()}, options);

        assertThat(expanded).contains("--se-port", "4446");
    }

    @Test
    void isTrue_acceptsCommonAffirmatives() {
        assertThat(ConfigFile.isTrue("true")).isTrue();
        assertThat(ConfigFile.isTrue("YES")).isTrue();
        assertThat(ConfigFile.isTrue("1")).isTrue();
        assertThat(ConfigFile.isTrue("on")).isTrue();
        // A bare "key =" in a properties file means "enable this flag".
        assertThat(ConfigFile.isTrue("")).isTrue();
        assertThat(ConfigFile.isTrue("false")).isFalse();
        assertThat(ConfigFile.isTrue("0")).isFalse();
        assertThat(ConfigFile.isTrue(null)).isFalse();
    }

    @Test
    void configValueWithEqualsSyntax_isDetectedOnCommandLine() throws Exception {
        Path file = write("se-port = 4446\n");

        String[] expanded = ConfigFile.expand(
                new String[]{"--config=" + file, "--se-port=9999"}, options);

        assertThat(String.join(" ", expanded)).doesNotContain("4446");
    }
}
