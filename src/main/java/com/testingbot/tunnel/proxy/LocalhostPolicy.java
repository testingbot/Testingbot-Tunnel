package com.testingbot.tunnel.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Whether requests arriving through the tunnel may reach the loopback interface of the machine
 * running it.
 *
 * <p>The tunnel exists so that a browser in TestingBot's cloud can reach things only this
 * machine can see, and for most users that means {@code http://localhost:3000}. So the default
 * is {@link #ALLOW}: denying by default would break the product's main use case.
 *
 * <p>{@link #DENY} is for the case where the tunnel is meant to reach a staging network and
 * nothing on the developer's own machine. Without it, anything able to drive a session through
 * the tunnel can also address services bound to loopback -- admin panels, databases, metadata
 * endpoints -- that are otherwise unreachable from outside.
 *
 * <p>This checks the host the request names, resolving it when it is not already an IP literal.
 * It is not a defence against DNS rebinding, where a name resolves to something harmless when
 * checked and to loopback when dialled a moment later; blocking that reliably means pinning the
 * checked address through to the connection.
 */
public enum LocalhostPolicy {

    ALLOW,
    DENY;

    /** Parses the {@code --localhost-policy} value, defaulting to {@link #ALLOW}. */
    public static LocalhostPolicy parse(String value) {
        if (value == null) {
            return ALLOW;
        }
        return "deny".equalsIgnoreCase(value.trim()) ? DENY : ALLOW;
    }

    /** True when {@code target} must be refused under this policy. */
    public boolean blocks(String target) {
        if (this == ALLOW || target == null) {
            return false;
        }
        return isLoopback(FastFailPolicy.normalise(target));
    }

    static boolean isLoopback(String host) {
        if (host.isEmpty()) {
            return false;
        }
        // Checked before resolving: "localhost" usually resolves, but must be refused even on a
        // machine where it does not.
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            return true;
        }
        try {
            // Every address, not just the first: a name answering with both a routable address
            // and a loopback one would otherwise slip through half the time.
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                    return true;
                }
            }
        } catch (UnknownHostException unresolvable) {
            // Nothing to reach, so nothing to protect; the dial will fail on its own and report
            // a DNS error, which is more useful than claiming a policy violation.
            return false;
        }
        return false;
    }
}
