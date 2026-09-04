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
 * <p>The two halves match differently, on purpose. A deny pattern matches anywhere in the host,
 * so it errs towards refusing; an exception must cover a whole host or a whole subdomain of one,
 * so it errs towards refusing too. Both asymmetries point the same way -- the reachable set only
 * ever shrinks when a pattern is ambiguous -- which is why {@code !ok\.com} excepts
 * {@code ok.com} and {@code www.ok.com} but not {@code ok.com.attacker.net}.
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
        if (!matchesAnyDeny(deny, host)) {
            return false;
        }
        return !matchesAnyException(allow, host);
    }

    /**
     * Deny patterns match as substrings, the behaviour this list has always had.
     *
     * <p>{@code --fast-fail-regexps facebook} refusing {@code www.facebook.com} is the whole
     * point of the option. Narrowing this would silently unblock destinations existing
     * configurations refuse today, which is the one direction a security check must never move
     * on its own.
     */
    private static boolean matchesAnyDeny(List<Pattern> patterns, String host) {
        for (Pattern p : patterns) {
            if (p.matcher(host).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exception patterns must match a whole host or a whole subdomain of one.
     *
     * <p>Substring matching here is the opposite direction and is a hole: with
     * {@code '.*,!ok\.com'} -- the form the {@code --help} text advertises -- a substring match
     * excepted {@code ok.com.attacker.net}, so the catch-all deny that was supposed to leave
     * exactly one origin reachable let an attacker choose any name they could register. Pointing
     * that name at a private address reached internal services too.
     *
     * <p>The rule is that the match must run to the end of the host and begin on a label
     * boundary: the start of the string, just after a dot, or on the dot itself (so a pattern
     * written as {@code (^|\.)staging\.example\.com$} still covers its subdomains). That keeps
     * both documented spellings working and refuses only the suffix trick -- {@code ok\.com}
     * excepts {@code ok.com} and {@code www.ok.com}, but neither {@code ok.com.attacker.net} nor
     * {@code notok.com}.
     *
     * <p>Not simply {@code matches()}: that would demand the pattern describe the entire host,
     * which breaks every exception written to cover a domain and its subdomains.
     */
    private static boolean matchesAnyException(List<Pattern> patterns, String host) {
        for (Pattern p : patterns) {
            java.util.regex.Matcher m = p.matcher(host);
            // From each position rather than from the end of the previous match. find() returns
            // the leftmost match and then resumes past it, so a longer match that fails the
            // boundary test would hide a shorter one after it that passes -- "(www\.)?ok\.com"
            // against "xwww.ok.com" matches at 1 and would never see the valid "ok.com" at 5.
            for (int from = 0; from <= host.length() && m.find(from); from = m.start() + 1) {
                if (m.end() != host.length()) {
                    // Something follows the match, so the pattern named a prefix of some other
                    // name rather than this host. This is the suffix trick.
                    continue;
                }
                int start = m.start();
                // start == host.length() is a zero-length match at the end, which "!x*" or a
                // trailing "|" produces. It matches nothing, so it excepts nothing -- and
                // charAt(start) there used to throw straight out through blocks().
                if (start == 0
                        || host.charAt(start - 1) == '.'
                        || (start < host.length() && host.charAt(start) == '.')) {
                    return true;
                }
                // Otherwise the match began mid-label ("staging.example.com" inside
                // "evilstaging.example.com"), which is a different name.
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
        // The root label is legal in a URL and means the same name, so leaving it on let
        // "example.com." walk past a pattern written for "example.com" -- and be refused by an
        // --allow-hosts entry that named it. A bare "." is not a name and is left alone.
        if (host.length() > 1 && host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
