package com.testingbot.tunnel;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mistyped options must produce a message, not a stack trace.
 *
 * <p>{@code setProxy} and {@code setProxyAuth} throw {@code IllegalArgumentException}, which
 * {@code main} does not catch — it handles {@code MissingArgumentException},
 * {@code ParseException} and {@code TunnelFailedException}. So two of the commonest values to
 * get wrong printed a raw trace while every other option managed one line.
 */
class CliErrorHandlingTest {

    private static CommandLine parse(String... args) throws ParseException {
        return new PosixParser().parse(App.buildOptions(), args);
    }

    private static App configured(String... args) throws Exception {
        App app = new App();
        App.applyOptions(app, parse(args));
        return app;
    }

    @Test
    void aProxyCredentialWithoutAColonIsAParseError() {
        assertThatThrownBy(() -> configured("--proxy-userpwd", "nocolon"))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("username:password");
    }

    @Test
    void anUnusableProxyValueIsAParseError() throws Exception {
        App app = new App();
        assertThatThrownBy(() ->
                App.applyUpstreamProxyOptions(app, parse("--proxy", "ftp://host:1")))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--proxy");
    }

    @Test
    void cacertFileDoesNotSwallowTheApiKeyAndSecret(@TempDir Path dir) throws Exception {
        // --cacert-file took UNLIMITED_VALUES, so the positional credentials were read as extra
        // file names and the error quoted the customer's API key back at them.
        Path pem = dir.resolve("ca.pem");
        Files.writeString(pem, "-----BEGIN CERTIFICATE-----\nnot really\n-----END CERTIFICATE-----\n");

        CommandLine line = parse("--cacert-file", pem.toString(), "MY_API_KEY", "MY_API_SECRET");

        assertThat(line.getOptionValues("cacert-file"))
                .as("only the file belongs to the option")
                .containsExactly(pem.toString());
        assertThat(line.getArgs())
                .as("the credentials stay positional")
                .containsExactly("MY_API_KEY", "MY_API_SECRET");
    }

    @Test
    void cacertFileIsStillRepeatable() throws Exception {
        // Dropping UNLIMITED_VALUES must not cost the documented ability to name several.
        CommandLine line = parse("--cacert-file", "/a.pem", "--cacert-file", "/b.pem");

        assertThat(line.getOptionValues("cacert-file")).containsExactly("/a.pem", "/b.pem");
    }

    @Test
    void theLauncherUnwrapsWhatEscapesAppMain() throws Exception {
        // Reflection made every uncaught error surface as InvocationTargetException with three
        // frames of plumbing above the real cause, so adding the launcher made errors harder to
        // read than before it existed.
        java.lang.reflect.Method main = Launcher.class.getDeclaredMethod("main", String[].class);
        assertThat(main.getExceptionTypes())
                .as("main rethrows the cause, so it must still declare Exception")
                .contains(Exception.class);
        String source = Files.readString(
                Path.of("src/main/java/com/testingbot/tunnel/Launcher.java"));
        assertThat(source).contains("InvocationTargetException");
        assertThat(source).contains("wrapped.getCause()");
    }
}
