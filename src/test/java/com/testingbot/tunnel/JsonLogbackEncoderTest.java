package com.testingbot.tunnel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logback half of {@code --log-format json}.
 *
 * <p>This replaced a PatternLayoutEncoder whose {@code %replace} escaping silently did nothing:
 * {@code %replace} takes one option group of two options, the pattern gave it two groups, and
 * the converter degraded to pass-through — so a quote or a backslash produced a line that was
 * not JSON, and the literal {@code {'"'}} was appended to every message. Nothing complained,
 * because logback.xml installs a NopStatusListener.
 *
 * <p>Every test therefore parses the output rather than matching substrings.
 */
class JsonLogbackEncoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode encode(String message, Throwable thrown) throws Exception {
        LoggerContext context = new LoggerContext();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.testingbot.tunnel.Example");
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        if (thrown != null) {
            event.setThrowableProxy(new ThrowableProxy(thrown));
        }
        JsonLogbackEncoder encoder = new JsonLogbackEncoder();
        encoder.setContext(context);
        encoder.start();
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(line).endsWith(System.lineSeparator());
        assertThat(line.trim()).doesNotContain("\n");
        return MAPPER.readTree(line);
    }

    @Test
    void anOrdinaryRecordBecomesOneJsonObject() throws Exception {
        JsonNode json = encode("tunnel ready", null);

        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("logger").asText()).isEqualTo("com.testingbot.tunnel.Example");
        assertThat(json.get("message").asText()).isEqualTo("tunnel ready");
        assertThat(json.get("timestamp").asText()).isNotEmpty();
    }

    @Test
    void aQuoteDoesNotBreakTheLine() throws Exception {
        // The exact input the pattern-based encoder emitted as invalid JSON.
        assertThat(encode("a \"quoted\" value", null).get("message").asText())
                .isEqualTo("a \"quoted\" value");
    }

    @Test
    void aBackslashDoesNotBreakTheLine() throws Exception {
        assertThat(encode("C:\\Users\\bob", null).get("message").asText())
                .isEqualTo("C:\\Users\\bob");
    }

    @Test
    void aNewlineStaysInsideTheOneRecord() throws Exception {
        assertThat(encode("line one\nline two", null).get("message").asText())
                .isEqualTo("line one\nline two");
    }

    @Test
    void aThrowableIsCarriedAsAnEscapedStackTrace() throws Exception {
        // A stack trace is multi-line and full of quotes, so it broke the pattern encoder in
        // both ways at once.
        JsonNode json = encode("could not connect",
                new IllegalStateException("bad \"state\""));

        assertThat(json.get("message").asText()).isEqualTo("could not connect");
        assertThat(json.get("exception").asText())
                .contains("IllegalStateException")
                .contains("bad \"state\"");
    }

    @Test
    void theLiteralReplaceOptionGroupNeverAppears() throws Exception {
        // The tell-tale of the broken pattern: {'"'} printed verbatim on every message.
        assertThat(encode("plain message", null).get("message").asText())
                .isEqualTo("plain message");
    }
}
