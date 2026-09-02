package com.testingbot.tunnel;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The logback half of {@code --log-format json}.
 *
 * <p>Both logging stacks write to the same file, so logback has to emit the same shape as
 * {@link JsonLogFormatter}. This was attempted with a {@code PatternLayoutEncoder} and a
 * {@code %replace} that escaped quotes. It did not work: {@code %replace} takes one option group
 * of two comma-separated options, the pattern passed two groups, and the converter degraded to
 * pass-through — so quotes were never escaped and the second group was printed literally.
 *
 * <pre>
 * a "quoted" value      -&gt; "message":"a "quoted" value{'"'}      invalid JSON
 * C:\Users\bob          -&gt; "message":"C:\Users\bob{'"'}          invalid JSON
 * a thrown exception    -&gt; three lines, unescaped                invalid JSON
 * </pre>
 *
 * <p>Nothing complained, because {@code logback.xml} installs a {@code NopStatusListener} and
 * the encoder still reported itself started. Escaping goes through Jackson here for the same
 * reason it does in JsonLogFormatter: a hand-rolled {@code replace} loses on the first backslash.
 */
public class JsonLogbackEncoder extends EncoderBase<ILoggingEvent> {

    private static final JsonStringEncoder ESCAPE = JsonStringEncoder.getInstance();

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        field(out, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        out.append(',');
        field(out, "level", String.valueOf(event.getLevel()));
        out.append(',');
        field(out, "logger", event.getLoggerName());
        out.append(',');
        field(out, "message", event.getFormattedMessage());

        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            out.append(',');
            field(out, "exception", ThrowableProxyUtil.asString(thrown));
        }
        out.append('}').append(System.lineSeparator());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append('"').append(name).append("\":\"");
        out.append(ESCAPE.quoteAsString(value == null ? "" : value));
        out.append('"');
    }
}
