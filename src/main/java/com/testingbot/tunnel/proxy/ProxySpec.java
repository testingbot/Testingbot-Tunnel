package com.testingbot.tunnel.proxy;

import java.util.Locale;

/**
 * An upstream proxy as given to {@code --proxy}.
 *
 * <p>Accepts {@code host:port} (HTTP, the historical form), {@code http://host:port} and
 * {@code socks5://host:port}. The scheme decides how both proxy paths reach the upstream:
 * the HTTP client uses Jetty's HttpProxy or Socks5Proxy, and CONNECT tunnelling issues
 * either an HTTP CONNECT or a SOCKS5 handshake.
 */
public final class ProxySpec {

    public enum Type { HTTP, SOCKS5 }

    private final Type type;
    private final String host;
    private final int port;

    private ProxySpec(Type type, String host, int port) {
        this.type = type;
        this.host = host;
        this.port = port;
    }

    public Type getType() {
        return type;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isSocks5() {
        return type == Type.SOCKS5;
    }

    /**
     * @return the parsed spec, or null when {@code spec} is blank or malformed
     */
    public static ProxySpec parse(String spec) {
        if (spec == null) {
            return null;
        }
        String value = spec.trim();
        if (value.isEmpty()) {
            return null;
        }

        Type type = Type.HTTP;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            String prefix = value.substring(0, scheme).toLowerCase(Locale.ROOT);
            switch (prefix) {
                case "socks5":
                case "socks5h":
                case "socks":
                    type = Type.SOCKS5;
                    break;
                case "http":
                    type = Type.HTTP;
                    break;
                default:
                    return null;
            }
            value = value.substring(scheme + 3);
        }

        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return null;
        }
        String host = value.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(value.substring(colon + 1));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new ProxySpec(type, host, port);
    }

    @Override
    public String toString() {
        return (type == Type.SOCKS5 ? "socks5://" : "http://") + host + ":" + port;
    }
}
