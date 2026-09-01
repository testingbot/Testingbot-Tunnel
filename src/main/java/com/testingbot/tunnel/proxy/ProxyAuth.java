package com.testingbot.tunnel.proxy;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Locale;

/**
 * The JVM-wide default {@link Authenticator} for {@code --proxy-userpwd}.
 *
 * <p>It used to answer every request with the proxy credentials -- no check of requestor type,
 * host, port or protocol -- so anything anywhere in the process that consulted the default
 * authenticator was handed the customer's proxy password. That included ordinary HTTP requests
 * to arbitrary origins, and, when the tunnel is embedded, the host application's own traffic.
 * Asking for {@code some.other.host:8080} over plain http returned the password.
 *
 * <p>It now answers only a proxy challenge, and only from the proxy it was configured for when
 * that is known. A credential issued for one proxy operator has no business reaching another
 * host that happens to ask.
 */
public class ProxyAuth extends Authenticator {

    private final PasswordAuthentication auth;
    private final String host;
    private final int port;
    private final Authenticator previous;

    public ProxyAuth(String user, String password) {
        this(user, password, null, -1, null);
    }

    public ProxyAuth(String user, String password, String host, int port) {
        this(user, password, host, port, null);
    }

    /**
     * @param host the proxy these credentials belong to, or null when it is not yet known --
     *             in which case any proxy challenge is answered, which is still far narrower
     *             than answering everything
     * @param port the proxy port, or -1 for any
     */
    /**
     * @param previous the authenticator this one replaces as the JVM default, consulted for
     *                 anything these credentials do not cover. Installing ourselves over a host
     *                 application's authenticator and answering null would break its
     *                 authentication silently.
     */
    public ProxyAuth(String user, String password, String host, int port, Authenticator previous) {
        this.previous = previous;
        this.auth = new PasswordAuthentication(user,
                password == null ? new char[]{} : password.toCharArray());
        this.host = host == null ? null : host.toLowerCase(Locale.ROOT);
        this.port = port;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        if (matches()) {
            return auth;
        }
        if (previous == null) {
            return null;
        }
        try {
            return Authenticator.requestPasswordAuthentication(previous, getRequestingHost(),
                    getRequestingSite(), getRequestingPort(), getRequestingProtocol(),
                    getRequestingPrompt(), getRequestingScheme(), getRequestingURL(),
                    getRequestorType());
        } catch (Throwable unusable) {
            return null;
        }
    }

    private boolean matches() {
        if (getRequestorType() != RequestorType.PROXY) {
            return false;
        }
        if (host == null) {
            return true;
        }
        String asked = getRequestingHost();
        if (asked == null || !host.equals(asked.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !(port > 0 && getRequestingPort() > 0 && port != getRequestingPort());
    }
}
