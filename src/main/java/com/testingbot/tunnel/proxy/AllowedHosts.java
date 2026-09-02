package com.testingbot.tunnel.proxy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The hosts a tunnel is permitted to reach, from {@code --allow-hosts}.
 *
 * <p>{@code --fast-fail-regexps} is a deny list: everything is reachable except what is named.
 * That is the wrong shape for a tunnel into a corporate network, where the question is not
 * "which hosts are forbidden" -- an unbounded set nobody can enumerate -- but "which hosts is
 * this tunnel for". A test that follows a link, an analytics beacon or a compromised dependency
 * can reach anything the machine running the tunnel can reach, and a deny list only helps for
 * destinations somebody thought of in advance.
 *
 * <p>When nothing is configured this permits everything, so the default is unchanged.
 *
 * <p>Unlike {@link NegotiateHosts} and {@link BumpPolicy}, subdomain patterns are supported here.
 * Those two are matched elsewhere -- by a KDC and by Squid -- so this client cannot define what a
 * pattern means; this list is matched here, so it can. {@code *.example.com} matches any
 * subdomain but not {@code example.com} itself, which is the convention everywhere else and
 * avoids a list that quietly grants more than it appears to.
 */
public final class AllowedHosts {

    private static final AllowedHosts UNRESTRICTED = new AllowedHosts(Set.of(), Set.of());

    /** Exact host names. */
    private final Set<String> exact;
    /** Suffixes from {@code *.example.com}, stored as {@code .example.com}. */
    private final Set<String> suffixes;

    private AllowedHosts(Set<String> exact, Set<String> suffixes) {
        this.exact = Set.copyOf(exact);
        this.suffixes = Set.copyOf(suffixes);
    }

    /** Permits everything; what an unconfigured tunnel uses. */
    public static AllowedHosts unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * @param entries comma-separated hosts, each an exact name or {@code *.suffix}
     * @throws IllegalArgumentException if an entry cannot be a host or a subdomain pattern
     */
    public static AllowedHosts parse(String entries) {
        if (entries == null || entries.isBlank()) {
            return UNRESTRICTED;
        }
        Set<String> exact = new LinkedHashSet<>();
        Set<String> suffixes = new LinkedHashSet<>();
        for (String raw : entries.split(",")) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            validate(entry);
            // A bracketed literal is how an IPv6 host is written in a URL, but it is not how a
            // destination arrives here: FastFailPolicy.normalise strips the brackets. Storing the
            // stripped form is what lets the two meet.
            if (entry.startsWith("[") && entry.endsWith("]")) {
                exact.add(entry.substring(1, entry.length() - 1));
                continue;
            }
            if (isIpv6Literal(entry)) {
                exact.add(entry);
                continue;
            }
            if (entry.startsWith("*.")) {
                suffixes.add(entry.substring(1));       // "*.example.com" -> ".example.com"
            } else if (entry.startsWith(".")) {
                suffixes.add(entry);                    // ".example.com", the same thing
            } else {
                exact.add(entry);
            }
        }
        return exact.isEmpty() && suffixes.isEmpty()
                ? UNRESTRICTED : new AllowedHosts(exact, suffixes);
    }

    private static void validate(String entry) {
        for (char forbidden : new char[]{'\r', '\n', '\t', ' '}) {
            if (entry.indexOf(forbidden) >= 0) {
                throw new IllegalArgumentException("Invalid --allow-hosts entry '" + entry
                        + "': a host cannot contain spaces or line breaks.");
            }
        }
        if (entry.contains("://") || entry.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Invalid --allow-hosts entry '" + entry
                    + "': give a host such as staging.example.com or *.example.com, not a URL.");
        }
        if (entry.indexOf(':') >= 0 && !isIpv6Literal(entry)
                && !(entry.startsWith("[") && entry.endsWith("]"))) {
            throw new IllegalArgumentException("Invalid --allow-hosts entry '" + entry
                    + "': the list is of hosts, so do not include a port.");
        }
        if (entry.equals("*") || entry.equals("*.") || entry.equals(".")) {
            // "*" would permit everything, which is what leaving the option out already does.
            // Accepting it would let a typo silently disable the whole policy.
            throw new IllegalArgumentException("Invalid --allow-hosts entry '" + entry
                    + "': to permit every host, leave --allow-hosts out entirely.");
        }
        int star = entry.indexOf('*');
        if (star >= 0 && !entry.startsWith("*.")) {
            throw new IllegalArgumentException("Invalid --allow-hosts entry '" + entry
                    + "': a wildcard is only supported as a leading '*.' for subdomains.");
        }
        if (entry.indexOf('*', star + 1) >= 0) {
            throw new IllegalArgumentException("Invalid --allow-hosts entry '" + entry
                    + "': only one leading '*.' is supported.");
        }
    }

    /**
     * True for {@code ::1} or {@code 2001:db8::1}, false for {@code example.com:8080}.
     *
     * <p>Without this every entry holding a colon was refused as "do not include a port", so no
     * IPv6 host could be named -- and since no entry could hold a colon, no IPv6 destination
     * could ever match either. Setting any list at all refused IPv6 everywhere, with no way to
     * write the entry that would have allowed it.
     */
    private static boolean isIpv6Literal(String entry) {
        if (entry.indexOf(':') < 0) {
            return false;
        }
        String candidate = entry.startsWith("[") && entry.endsWith("]")
                ? entry.substring(1, entry.length() - 1) : entry;
        // Two colons, or one colon and no digits-only tail, is not a host:port.
        if (candidate.indexOf(':') == candidate.lastIndexOf(':')) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.' && c != '%') {
                return false;
            }
        }
        return true;
    }

    /** True when no list was configured, so every host is permitted. */
    public boolean isUnrestricted() {
        return exact.isEmpty() && suffixes.isEmpty();
    }

    /**
     * True when {@code host} may be reached.
     *
     * <p>An unknown host is refused rather than permitted: a policy that fails open is not a
     * policy. A null host is refused for the same reason.
     */
    public boolean permits(String host) {
        if (isUnrestricted()) {
            return true;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        String candidate = FastFailPolicy.normalise(host).toLowerCase(Locale.ROOT);
        if (exact.contains(candidate)) {
            return true;
        }
        for (String suffix : suffixes) {
            // "*.example.com" grants sub.example.com but not example.com, so that widening the
            // list to the apex has to be written down.
            if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    /** The entries as configured, for diagnostics. */
    public List<String> entries() {
        List<String> out = new ArrayList<>(exact);
        for (String suffix : suffixes) {
            out.add("*" + suffix);
        }
        return out;
    }

    @Override
    public String toString() {
        return isUnrestricted() ? "any host" : String.join(", ", entries());
    }
}
