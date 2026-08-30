package com.testingbot.tunnel.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides which destinations {@code --fast-fail-regexps} should refuse.
 *
 * <p>The list used to be deny-only, which cannot express "block everything except our staging
 * host" -- a common thing to want when a test run should reach exactly one origin and any other
 * request means something has gone wrong. An entry prefixed with {@code !} is an exception:
 *
 * <pre>{@code   --fast-fail-regexps '.*,!(^|\.)staging\.example\.com$'}</pre>
 *
 * <p>A host is refused when some deny pattern matches it and no exception does. Exceptions win,
 * so order does not matter. Lists without {@code !} behave exactly as before.
 *
 * <p>Both the plain-HTTP and CONNECT paths share this, so a pattern cannot mean one thing for
 * {@code http://} and another for {@code https://}.
 */
public final class FastFailPolicy {

    private static final Logger LOG = Logger.getLogger(FastFailPolicy.class.getName());

    private static final FastFailPolicy EMPTY =
            new FastFailPolicy(Collections.emptyList(), Collections.emptyList());

    private final List<Pattern> deny;
    private final List<Pattern> allow;

    private FastFailPolicy(List<Pattern> deny, List<Pattern> allow) {
        this.deny = deny;
        this.allow = allow;
    }

    /** A policy that blocks nothing. */
    public static FastFailPolicy none() {
        return EMPTY;
    }

    public static FastFailPolicy compile(String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            return EMPTY;
        }
        List<Pattern> deny = new ArrayList<>(patterns.length);
        List<Pattern> allow = new ArrayList<>();
        for (String entry : patterns) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            boolean exception = trimmed.startsWith("!");
            if (exception) {
                trimmed = trimmed.substring(1).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                (exception ? allow : deny).add(Pattern.compile(trimmed));
            } catch (PatternSyntaxException ex) {
                LOG.log(Level.WARNING, "Invalid fast-fail pattern ''{0}'' ignored: {1}",
                        new Object[]{trimmed, ex.getDescription()});
            }
        }
        if (deny.isEmpty() && allow.isEmpty()) {
            return EMPTY;
        }
        return new FastFailPolicy(Collections.unmodifiableList(deny),
                Collections.unmodifiableList(allow));
    }

    /** True when nothing was configured, so callers can skip the work entirely. */
    public boolean isEmpty() {
        return deny.isEmpty() && allow.isEmpty();
    }

    /**
     * @param target a bare host, a {@code host:port} authority, or a bracketed IPv6 literal --
     *               CONNECT and plain HTTP hand us different shapes
     */
    public boolean blocks(String target) {
        if (target == null || deny.isEmpty()) {
            // With only exceptions configured there is nothing to be excepted from.
            return false;
        }
        String host = normalise(target);
        if (!matchesAny(deny, host)) {
            return false;
        }
        return !matchesAny(allow, host);
    }

    private static boolean matchesAny(List<Pattern> patterns, String host) {
        for (Pattern p : patterns) {
            if (p.matcher(host).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reduces an authority to the bare host.
     *
     * <p>Truncating at the first colon turned {@code [::1]} into {@code [}, so no pattern could
     * ever match an IPv6 destination.
     */
    static String normalise(String target) {
        String host = target;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(1, close) : host;
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0 && host.indexOf(':', colon + 1) < 0) {
                host = host.substring(0, colon);   // host:port, not a bare IPv6 literal
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
