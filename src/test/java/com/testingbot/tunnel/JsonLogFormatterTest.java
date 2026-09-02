package com.testingbot.tunnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --log-format json}.
 *
 * <p>Every test parses the output rather than matching substrings. A formatter that emits
 * almost-JSON is worse than one that emits text: the collector accepts most lines and silently
 * drops the ones that happen to contain a quote, which are disproportionately the interesting
 * ones.
 */
class JsonLogFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode format(LogRecord record) throws Exception {
        String line = new JsonLogFormatter().format(record);
        assertThat(line).endsWith(System.lineSeparator());
        return MAPPER.readTree(line);
    }

    private static LogRecord record(Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("com.testingbot.tunnel.Example");
        return record;
    }

    @Test
    void aRecordBecomesOneJsonObject() throws Exception {
        JsonNode json = format(record(Level.INFO, "tunnel ready"));

        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("logger").asText()).isEqualTo("com.testingbot.tunnel.Example");
        assertThat(json.get("message").asText()).isEqualTo("tunnel ready");
        assertThat(json.get("timestamp").asText()).isNotEmpty();
    }

    @Test
    void parametersAreSubstituted() throws Exception {
        // getMessage() returns the raw "{0}" pattern. Logging that would be useless, and it is
        // a mistake this codebase has made in tests more than once.
        LogRecord record = record(Level.INFO, "Setting up port {0}");
        record.setParameters(new Object[]{"8087"});

        assertThat(format(record).get("message").asText()).isEqualTo("Setting up port 8087");
    }

    @Test
    void theMessageIsIdenticalToWhatTextLoggingWouldHaveWritten() throws Exception {
        // Changing --log-format must change the framing and nothing else. Asserting a literal
        // here instead would pin down MessageFormat's own quirks -- it renders an int parameter
        // as "8,087", with a thousands separator, in both formats -- as though they were this
        // class's behaviour.
        LogRecord record = record(Level.INFO, "Setting up port {0}");
        record.setParameters(new Object[]{8087});

        String fromText = new LogFormatter().formatMessage(record);
        assertThat(format(record).get("message").asText()).isEqualTo(fromText);
    }

    @Test
    void quotesAndBackslashesDoNotBreakTheLine() throws Exception {
        // The reason escaping goes through Jackson rather than a replace(): a header value or a
        // Windows path in a message would otherwise produce a line that is not JSON.
        JsonNode json = format(record(Level.WARNING,
                "rejected \"Authorization: Bearer x\" from C:\\Users\\test"));

        assertThat(json.get("message").asText())
                .isEqualTo("rejected \"Authorization: Bearer x\" from C:\\Users\\test");
    }

    @Test
    void newlinesStayInsideTheOneRecord() throws Exception {
        // A multi-line message is exactly what a collector gets wrong with text output, so it
        // must not produce more than one line here.
        JsonNode json = format(record(Level.INFO, "line one\nline two\r\nline three"));

        assertThat(json.get("message").asText()).isEqualTo("line one\nline two\r\nline three");
        assertThat(new JsonLogFormatter().format(record(Level.INFO, "a\nb")).trim())
                .doesNotContain("\n");
    }

    @Test
    void controlCharactersAreEscaped() throws Exception {
        JsonNode json = format(record(Level.INFO, "tab\there\u0000null"));

        assertThat(json.get("message").asText()).isEqualTo("tab\there\u0000null");
    }

    @Test
    void anExceptionIsCarriedAsItsStackTrace() throws Exception {
        LogRecord record = record(Level.SEVERE, "could not connect");
        record.setThrown(new java.net.ConnectException("Connection refused"));

        JsonNode json = format(record);

        assertThat(json.get("message").asText()).isEqualTo("could not connect");
        assertThat(json.get("exception").asText())
                .contains("ConnectException")
                .contains("Connection refused");
    }

    @Test
    void aRecordWithNoLoggerOrMessageIsStillValidJson() throws Exception {
        // Defensive: a null logger name would otherwise write the literal "null" or throw, and
        // a formatter that throws takes down whatever was trying to log.
        JsonNode json = format(new LogRecord(Level.INFO, null));

        assertThat(json.get("logger").asText()).isEmpty();
        assertThat(json.has("message")).isTrue();
    }

    @Test
    void theChoiceIsWiredToTheOption() {
        assertThat(App.logFormatterFor("json")).isInstanceOf(JsonLogFormatter.class);
        assertThat(App.logFormatterFor("JSON")).isInstanceOf(JsonLogFormatter.class);
        assertThat(App.logFormatterFor("text")).isInstanceOf(LogFormatter.class);
        assertThat(App.logFormatterFor(null)).isInstanceOf(LogFormatter.class);
    }
}
