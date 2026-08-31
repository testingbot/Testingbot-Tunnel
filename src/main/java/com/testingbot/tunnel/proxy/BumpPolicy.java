package com.testingbot.tunnel.proxy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which destinations should have their TLS passed through untouched rather than re-signed.
 *
 * <p>SSL bumping happens on TestingBot's Squid, not here: the tunnel relays the decision at
 * creation time and the server acts on it. So this class only parses and validates what the
 * user asked for, and produces the value the API is given.
 *
 * <p>{@code --nobump} is the whole-tunnel form and {@code --nobump-domains} the per-destination
 * one. A tunnel that reaches several environments usually needs bumping off for one of them --
 * a host presenting a certificate Squid cannot chain, typically -- without giving up the
 * rewriting everywhere else, and the boolean cannot express that.
 */
public final class BumpPolicy {

    /** Rejected outright: these would corrupt the form body the tunnel is created with. */
    private static final String FORBIDDEN = "\r\n\t ,";

    private final boolean allDomains;
    private final List<String> domains;

    private BumpPolicy(boolean allDomains, List<String> domains) {
        this.allDomains = allDomains;
        this.domains = List.copyOf(domains);
    }

    /**
     * @param noBump  the {@code --nobump} flag, meaning every destination
     * @param entries the {@code --nobump-domains} value, comma separated, or null
     * @throws IllegalArgumentException if an entry cannot be a host name
     */
    public static BumpPolicy parse(boolean noBump, String entries) {
        List<String> parsed = new ArrayList<>();
        if (entries != null && !entries.isBlank()) {
            // A set, in order: repeating a host is harmless but sending it twice is noise.
            Set<String> seen = new LinkedHashSet<>();
            for (String raw : entries.split(",")) {
                String domain = raw.trim().toLowerCase(Locale.ROOT);
                if (domain.isEmpty()) {
                    continue;
                }
                validate(domain);
                seen.add(domain);
            }
            parsed.addAll(seen);
        }
        return new BumpPolicy(noBump, parsed);
    }

    private static void validate(String domain) {
        for (int i = 0; i < FORBIDDEN.length(); i++) {
            if (domain.indexOf(FORBIDDEN.charAt(i)) >= 0) {
                throw new IllegalArgumentException(
                        "Invalid --nobump-domains entry '" + domain + "': it must be a host name,"
                        + " without spaces or line breaks.");
            }
        }
        // A scheme or a path is the most common way to write this wrongly, and silently
        // treating "https://example.com/" as a host name would never match anything.
        if (domain.contains("://") || domain.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid --nobump-domains entry '" + domain + "': give a host name such as"
                    + " staging.example.com, not a URL.");
        }
        if (domain.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "Invalid --nobump-domains entry '" + domain + "': bumping applies to a host,"
                    + " so do not include a port.");
        }
    }

    /** True when nothing should be bumped anywhere. */
    public boolean isAllDomains() {
        return allDomains;
    }

    /** The named hosts, empty when none were given. */
    public List<String> domains() {
        return domains;
    }

    /** True when the whole tunnel is unbumped, or at least one host was named. */
    public boolean isConfigured() {
        return allDomains || !domains.isEmpty();
    }

    /**
     * The value for the API's {@code no_bump_domains} parameter, or null when there is nothing
     * per-host to send.
     *
     * <p>Null when {@code --nobump} is set: that already turns bumping off everywhere, so a list
     * alongside it could only narrow what is already total, and sending both would leave the
     * server to guess which was meant.
     */
    public String apiValue() {
        if (allDomains || domains.isEmpty()) {
            return null;
        }
        return String.join(",", domains);
    }

    @Override
    public String toString() {
        if (allDomains) {
            return "all domains";
        }
        return domains.isEmpty() ? "none" : String.join(", ", domains);
    }
}
