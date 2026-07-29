package com.testingbot.tunnel;

/**
 * Thrown when the tunnel cannot be started or has to be aborted.
 *
 * Carries the exit code the command line client should terminate with, so that
 * {@link App#main(String...)} keeps its existing exit codes while embedders can
 * catch this instead of having their JVM terminated.
 */
public class TunnelFailedException extends RuntimeException {
    private final int exitCode;

    public TunnelFailedException(String message) {
        this(message, 1);
    }

    public TunnelFailedException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public TunnelFailedException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
