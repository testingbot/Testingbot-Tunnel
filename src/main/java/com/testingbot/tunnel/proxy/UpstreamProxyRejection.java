package com.testingbot.tunnel.proxy;

import java.io.IOException;

/**
 * The upstream proxy answered with an HTTP status other than success.
 *
 * <p>Carries the status code so {@link ProxyErrors} can classify on it. It used to search the
 * message instead, and the message names the proxy's host and port: a 403 from a proxy on port
 * 40797 contains "407", so a refusal was reported as rejected credentials whenever the port
 * happened to have those digits in it.
 */
final class UpstreamProxyRejection extends IOException {

    private final int status;

    UpstreamProxyRejection(String message, String statusLine) {
        super(message);
        this.status = HttpStatusLine.parse(statusLine);
    }

    /** The proxy's status code, or {@link HttpStatusLine#INVALID} if it sent no status line. */
    int status() {
        return status;
    }

    /** 407 is the proxy asking for credentials, or turning down the ones it was given. */
    boolean isAuthenticationFailure() {
        return status == 407;
    }
}
