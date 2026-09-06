package com.testingbot.tunnel;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --log-format json} has to make the whole console stream JSON.
 *
 * <p>This process logs through two stacks: JUL for its own classes, and SLF4J/logback for Jetty,
 * Apache HC and the proxy handlers. Only the JUL side was reformatted, and {@code logback.xml}
 * pins its console appender to a text pattern -- so the option produced a stream that was JSON
 * for some records and text for others. That is not a format at all, and it is worse for the
 * collector this option exists to serve than plain text would have been, because it parses most
 * of the way and then fails.
 */
class JsonLogFormatWiringTest {

    private final List<Runnable> restore = new ArrayList<>();

    @AfterEach
    void tearDown() {
        restore.forEach(Runnable::run);
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static List<Appender<ILoggingEvent>> rootAppenders(LoggerContext context) {
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
                .iteratorForAppenders().forEachRemaining(appenders::add);
        return appenders;
    }

    @Test
    void theLogbackConsoleAppenderGetsTheJsonEncoder() {
        LoggerContext context = context();
        List<Appender<ILoggingEvent>> before = rootAppenders(context);
        ConsoleAppender<ILoggingEvent> console = before.stream()
                .filter(a -> a instanceof ConsoleAppender)
                .map(a -> {
                    @SuppressWarnings("unchecked")
                    ConsoleAppender<ILoggingEvent> typed = (ConsoleAppender<ILoggingEvent>) a;
                    return typed;
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "logback.xml is expected to configure a console appender"));

        Encoder<ILoggingEvent> original = console.getEncoder();
        restore.add(() -> {
            console.stop();
            console.setEncoder(original);
            console.start();
        });

        assertThat(original)
                .as("the default is the text pattern, which is the whole problem")
                .isNotInstanceOf(JsonLogbackEncoder.class);

        App.jsonifyLogbackConsole(context);

        assertThat(console.getEncoder())
                .as("Jetty, Apache HC and the proxy handlers write through this appender")
                .isInstanceOf(JsonLogbackEncoder.class);
        assertThat(console.isStarted())
                .as("an appender left stopped would drop every record it carries")
                .isTrue();
    }

    @Test
    void theAppenderIsReplacedInPlaceRatherThanAdded() {
        LoggerContext context = context();
        int before = rootAppenders(context).size();
        ConsoleAppender<ILoggingEvent> console = rootAppenders(context).stream()
                .filter(a -> a instanceof ConsoleAppender)
                .map(a -> {
                    @SuppressWarnings("unchecked")
                    ConsoleAppender<ILoggingEvent> typed = (ConsoleAppender<ILoggingEvent>) a;
                    return typed;
                })
                .findFirst()
                .orElseThrow();
        Encoder<ILoggingEvent> original = console.getEncoder();
        restore.add(() -> {
            console.stop();
            console.setEncoder(original);
            console.start();
        });

        App.jsonifyLogbackConsole(context);

        // Adding a second appender would have been the easy implementation and would emit every
        // record twice -- once as JSON and once as text, which is the original defect plus
        // duplication.
        assertThat(rootAppenders(context)).hasSize(before);
    }

    @Test
    void theEncoderProducesOneParseableObjectPerRecord() throws Exception {
        // The encoder is what the whole option rests on, so its output is checked as JSON
        // rather than by eye.
        JsonLogbackEncoder encoder = new JsonLogbackEncoder();
        encoder.setContext(context());
        encoder.start();

        ch.qos.logback.classic.Logger logger = context().getLogger("test.logger");
        ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent();
        event.setLoggerName("test.logger");
        event.setLevel(ch.qos.logback.classic.Level.WARN);
        // Quotes, backslashes and a newline: the characters a hand-rolled escape loses on.
        event.setMessage("a \"quoted\" \\ message\nwith a newline");
        event.setTimeStamp(System.currentTimeMillis());

        String line = new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(line).endsWith("\n");
        com.fasterxml.jackson.databind.JsonNode parsed =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
        assertThat(parsed.get("message").asText())
                .isEqualTo("a \"quoted\" \\ message\nwith a newline");
        assertThat(parsed.get("level").asText()).isEqualTo("WARN");
        assertThat(parsed.get("logger").asText()).isEqualTo("test.logger");
    }
}
