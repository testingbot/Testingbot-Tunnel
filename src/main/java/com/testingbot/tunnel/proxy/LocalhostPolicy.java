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
 * <p>Enforced twice, and the second time is the one that counts. {@link #blocks(String,
 * HostResolver)} runs before the request is accepted, resolving the name so a refusal can be
 * reported as a policy error rather than a connection failure. {@link #blocksAddress} then runs
 * on the address actually about to be dialled.
 *
 * <p>The early check alone was not a defence against DNS rebinding: the dial resolved the name a
 * second time, from scratch, so an attacker-controlled name could answer with a routable address
 * when checked and loopback when dialled. With {@code --dns} set, {@code CustomDnsResolver}
 * disables its cache outright, which made the two lookups independent by construction and the
 * bypass deterministic rather than a race. Checking the dialled address closes the window
 * because there is no second lookup between the decision and the connection -- it is the same
 * address.
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
        return blocks(target, null);
    }

    /**
     * @param resolver the resolver the dial will use, so the policy and the connection agree on
     *                 what a name means. Checking against the platform resolver while dialling
     *                 through {@code --dns} let a name look external here and resolve to
     *                 loopback a moment later, which defeats the point of denying it.
     */
    public boolean blocks(String target, HostResolver resolver) {
        if (this == ALLOW || target == null) {
            return false;
        }
        return isLoopback(FastFailPolicy.normalise(target), resolver);
    }

    /**
     * True when an address about to be dialled must be refused.
     *
     * <p>No resolution: the caller has already resolved, and re-resolving here would reopen the
     * very gap this exists to close. Also covers {@code --connect-to}, whose remapping happens
     * after the name check, so its target is judged as what gets dialled rather than as what the
     * client asked for.
     */
    public boolean blocksAddress(InetAddress address) {
        if (this == ALLOW || address == null) {
            return false;
        }
        return address.isLoopbackAddress() || address.isAnyLocalAddress();
    }

    /**
     * Refuses a dial that {@link #blocksAddress} rejects.
     *
     * <p>Unchecked because it is thrown from Jetty callbacks that declare no exception. Both
     * {@code ConnectHandler.connectToServer} and the WebSocket dial run their address lookup
     * inside a try/catch that fails the connection, so this surfaces as a failed connect rather
     * than as a crash.
     */
    public static final class Denied extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Denied(String host, InetAddress address) {
            super("--localhost-policy deny: " + host + " resolved to " + address.getHostAddress()
                    + " at dial time");
        }
    }

    static boolean isLoopback(String host) {
        return isLoopback(host, null);
    }

    static boolean isLoopback(String host, HostResolver resolver) {
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
            for (InetAddress address : resolve(host, resolver)) {
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

    private static InetAddress[] resolve(String host, HostResolver resolver)
            throws UnknownHostException {
        return resolver == null ? InetAddress.getAllByName(host) : resolver.resolve(host);
    }

    /**
     * Name resolution, narrowed to what this policy needs.
     *
     * <p>An interface rather than CustomDnsResolver directly: that class is final with a private
     * constructor, and the policy only ever needs "what does this name resolve to".
     */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
