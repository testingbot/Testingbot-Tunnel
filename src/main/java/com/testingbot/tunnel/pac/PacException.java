package com.testingbot.tunnel.pac;

/**
 * A PAC file could not be parsed or evaluated.
 *
 * <p>Carries the line so a customer can find the construct in their own file. PAC files are
 * often generated or decades old, and "unsupported syntax" without a location is unactionable.
 */
public class PacException extends RuntimeException {

    private final int line;

    public PacException(String message, int line) {
        super(line > 0 ? message + " (line " + line + ")" : message);
        this.line = line;
    }

    public PacException(String message) {
        this(message, 0);
    }

    public PacException(String message, Throwable cause) {
        super(message, cause);
        this.line = 0;
    }

    public int getLine() {
        return line;
    }
}
