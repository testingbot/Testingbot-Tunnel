package com.testingbot.tunnel.proxy;

/**
 * Reads the status code out of an HTTP status line.
 *
 * <p>Both hand-rolled relays here have to interpret a response they read off the wire themselves
 * rather than through Jetty's parser: {@link CustomConnectHandler} reads the upstream proxy's
 * answer to a CONNECT, and {@link WebsocketHandler} reads the target's answer to an upgrade.
 *
 * <p>They did it differently. The CONNECT path parsed the line properly; the WebSocket path
 * asked whether the first line {@code contains("101")}, which accepts {@code HTTP/1.1 2101}, a
 * {@code 500} whose reason phrase mentions 101, and anything else with those three digits
 * anywhere in it -- and then treated the connection as an established WebSocket, handing the
 * client a 101 of our own and splicing it to a target that had refused. One parser, used by
 * both, so the two cannot drift again.
 */
final class HttpStatusLine {

    /** No status could be read. Distinct from any real code, so callers need no second check. */
    static final int INVALID = -1;

    private HttpStatusLine() {
    }

    /**
     * @param statusLine e.g. {@code HTTP/1.1 101 Switching Protocols}
     * @return the three-digit status, or {@link #INVALID} if this is not a status line
     */
    static int parse(String statusLine) {
        if (statusLine == null) {
            return INVALID;
        }
        String[] parts = statusLine.trim().split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            return INVALID;
        }
        // Exactly three digits. Integer.parseInt alone would accept "2101" and "+200", and it is
        // the over-long case that the substring check used to let through.
        String code = parts[1];
        if (code.length() != 3) {
            return INVALID;
        }
        for (int i = 0; i < 3; i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                return INVALID;
            }
        }
        return Integer.parseInt(code);
    }

    /** True for a 2xx, which is what a proxy answering CONNECT must send. */
    static boolean isSuccessfulConnect(String statusLine) {
        int code = parse(statusLine);
        return code >= 200 && code < 300;
    }

    /** True only for {@code 101 Switching Protocols}. */
    static boolean isSwitchingProtocols(String statusLine) {
        return parse(statusLine) == 101;
    }
}
