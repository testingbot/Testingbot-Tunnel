package com.testingbot.tunnel;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * One JSON object per log record, for {@code --log-format json}.
 *
 * <p>Written for log collectors. The tunnel's default output is meant for a person watching a
 * terminal, and a collector ingesting it has to guess at where one record ends and the next
 * begins -- which a stack trace, or any message containing a newline, gets wrong.
 *
 * <p>Escaping goes through Jackson, which is already a dependency, rather than a hand-rolled
 * {@code replace("\"", "\\\"")}: a message carrying a quote, a backslash or a control character
 * would otherwise produce a line that is not JSON at all, and the collector would drop it
 * exactly when something interesting was being logged.
 */
public class JsonLogFormatter extends Formatter {

    private static final JsonStringEncoder ESCAPE = JsonStringEncoder.getInstance();

    @Override
    public String format(LogRecord record) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        field(out, "timestamp", Instant.ofEpochMilli(record.getMillis()).toString());
        out.append(',');
        field(out, "level", record.getLevel().getName());
        out.append(',');
        field(out, "logger", record.getLoggerName() == null ? "" : record.getLoggerName());
        out.append(',');
        // formatMessage, not getMessage: the latter is the raw "{0}" pattern with the
        // parameters unsubstituted, which is not what anyone reading a log wants.
        field(out, "message", formatMessage(record));

        if (record.getThrown() != null) {
            out.append(',');
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            field(out, "exception", trace.toString());
        }
        out.append('}').append(System.lineSeparator());
        return out.toString();
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append('"').append(name).append("\":\"");
        out.append(ESCAPE.quoteAsString(value == null ? "" : value));
        out.append('"');
    }
}
