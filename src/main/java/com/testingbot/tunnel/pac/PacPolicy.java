package com.testingbot.tunnel.pac;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads a PAC file and answers "where should this host's traffic go".
 *
 * <p>Wraps {@link PacInterpreter} with the two things a proxy needs on top of evaluation: the
 * file has to come from somewhere (a path or a URL, as browsers accept both), and the answer has
 * to be cheap enough to ask on every request.
 *
 * <p>Results are cached by URL and host, with expiry for time-dependent predicates. The cache is bounded, because a tunnel can be pointed at a very large
 * number of hosts and an unbounded map here would be a slow leak.
 */
public final class PacPolicy {

    private static final Logger LOG = Logger.getLogger(PacPolicy.class.getName());

    /** Enough for any realistic test run; beyond this the cache is cleared rather than grown. */
    static final int MAX_CACHE_ENTRIES = 4096;

    /**
     * How long a decision is reused.
     *
     * <p>PAC files can route by time of day -- {@code timeRange(9, 17)} and
     * {@code weekdayRange("MON", "FRI")} are in the standard function set -- so a decision cached
     * without expiry freezes the first answer for the life of the tunnel. A session started at
     * 08:50 would keep using the out-of-hours route all day, which is precisely the kind of fault
     * nobody traces back to a cache.
     *
     * <p>A minute is short enough that a time-based rule takes effect promptly and long enough
     * that a burst of requests to one host costs a single evaluation.
     */
    static final long CACHE_TTL_MS = 60_000;

    private static final int FETCH_TIMEOUT_MS = 15_000;

    /**
     * A PAC file is a few kilobytes of JavaScript. Reading an unbounded response into memory on
     * the strength of a URL the user supplied -- which may be a corporate endpoint having a bad
     * day, or simply the wrong address -- is not something to do on startup.
     */
    static final int MAX_PAC_BYTES = 1024 * 1024;

    /** A decision and when it was made. */
    private record CachedResult(PacResult result, long decidedAtMs) {
    }

    private final PacInterpreter interpreter;
    private final String source;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    PacPolicy(PacInterpreter interpreter, String source) {
        this(interpreter, source, System::currentTimeMillis);
    }

    PacPolicy(PacInterpreter interpreter, String source, java.util.function.LongSupplier clock) {
        this.interpreter = interpreter;
        this.source = source;
        this.clock = clock;
    }

    /**
     * How to reach a PAC document that lives behind the network's own egress rules.
     *
     * <p>The fetch used a bare {@link HttpURLConnection}, so it ignored both {@code --proxy} and
     * {@code --cacert-file}. On a proxy-only network the PAC URL was simply unreachable, and on
     * a TLS-intercepting network -- which is the entire reason {@code --cacert-file} exists --
     * an {@code https} PAC URL failed the handshake against a CA the JVM has never seen. Either
     * way the tunnel refused to start over a document it had been told how to reach.
     *
     * @param proxy the upstream proxy to fetch through, or null for a direct fetch
     * @param sslSocketFactory the factory carrying any extra CAs, or null for the platform's
     */
    public record FetchOptions(java.net.Proxy proxy,
                               javax.net.ssl.SSLSocketFactory sslSocketFactory) {
    }

    /**
     * @param location a file path, or an https URL
     * @throws PacException if it cannot be read or does not parse
     */
    public static PacPolicy load(String location) {
        return load(location, null, null);
    }

    /**
     * @param location a file path, or an http(s) URL
     * @param expectedSha256 the hex SHA-256 the fetched document must have, or null
     * @throws PacException if it cannot be read, fails its digest, or does not parse
     */
    public static PacPolicy load(String location, String expectedSha256) {
        return load(location, expectedSha256, null);
    }

    /**
     * @param options how to reach a remote document, or null to fetch it directly
     * @throws PacException if it cannot be read, fails its digest, or does not parse
     */
    public static PacPolicy load(String location, String expectedSha256, FetchOptions options) {
        String text = read(location, expectedSha256, options);
        try {
            return new PacPolicy(new PacInterpreter(text), location);
        } catch (PacException invalid) {
            // getMessage() already carries the line, so do not pass it again.
            throw new PacException("PAC file " + location + " is not usable: "
                    + invalid.getMessage());
        }
    }

    /** For tests and diagnostics, where the script is already in hand. */
    public static PacPolicy of(String script, String description) {
        return new PacPolicy(new PacInterpreter(script), description);
    }

    private static String read(String location, String expectedSha256, FetchOptions options) {
        // Before anything is fetched. A pin that cannot match is a configuration error, and
        // discovering it only after the document has been pulled over cleartext means the
        // request went out anyway -- to exactly the network this pin exists to distrust.
        requireWellFormedDigest(expectedSha256);
        if (location.startsWith("http://") || location.startsWith("https://")) {
            boolean plaintext = location.startsWith("http://");
            if (plaintext && expectedSha256 == null) {
                // This document decides where every byte of egress goes, and on the CONNECT path
                // it decides who receives the --proxy-userpwd credential. Fetched in cleartext it
                // is whatever the network says it is, and the classic position for that attack --
                // owning the WPAD name on a corporate LAN -- is exactly where --pac-local is
                // used. Refused rather than warned about: a warning on a startup line nobody
                // reads is not a decision the operator made.
                throw new PacException("Refusing to fetch PAC file " + location
                        + " over plain HTTP: its contents decide where traffic and upstream "
                        + "proxy credentials are sent, and nothing authenticates them. Use an "
                        + "https:// URL or a local file path, or pin the document with "
                        + "--pac-local-sha256 if the http URL cannot be changed.");
            }
            HttpURLConnection connection = null;
            try {
                java.net.Proxy proxy = options == null ? null : options.proxy();
                connection = (HttpURLConnection) (proxy == null
                        ? URI.create(location).toURL().openConnection()
                        : URI.create(location).toURL().openConnection(proxy));
                if (options != null && options.sslSocketFactory() != null
                        && connection instanceof javax.net.ssl.HttpsURLConnection https) {
                    https.setSSLSocketFactory(options.sslSocketFactory());
                }
                connection.setConnectTimeout(FETCH_TIMEOUT_MS);
                connection.setReadTimeout(FETCH_TIMEOUT_MS);
                // Not followed: HttpURLConnection follows by default and will not carry an
                // https:// fetch down to http://, but it will happily follow one https host to
                // another. Either way the document would come from somewhere other than the
                // place the operator named, which is the thing being established here.
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    throw new PacException("PAC file " + location + " answered HTTP " + status
                            + " (a redirect). Name the final URL directly, so what is fetched is "
                            + "what was configured.");
                }
                if (status != 200) {
                    throw new PacException("Could not fetch PAC file " + location
                            + ": HTTP " + status);
                }
                byte[] body = connection.getInputStream()
                        .readNBytes(MAX_PAC_BYTES + 1);
                if (body.length > MAX_PAC_BYTES) {
                    throw new PacException("PAC file " + location + " is larger than "
                            + MAX_PAC_BYTES + " bytes; refusing to load it");
                }
                verifyDigest(location, body, expectedSha256);
                return new String(body, StandardCharsets.UTF_8);
            } catch (IOException unreachable) {
                throw new PacException("Could not fetch PAC file " + location + ": "
                        + unreachable.getMessage(), unreachable);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        try {
            // The same cap the remote path applies. readAllBytes() had none, so a path that
            // was not the small script it was meant to be -- a log, a device, the wrong file
            // entirely -- was read into memory in full before anything looked at it. The limit
            // is about what this process will hold, and that does not depend on where the
            // bytes came from.
            byte[] body;
            try (java.io.InputStream in = Files.newInputStream(Path.of(location))) {
                body = in.readNBytes(MAX_PAC_BYTES + 1);
            }
            if (body.length > MAX_PAC_BYTES) {
                throw new PacException("PAC file " + location + " is larger than "
                        + MAX_PAC_BYTES + " bytes; refusing to load it");
            }
            // A digest given for a local file is checked too. It is not defending against the
            // network here, but it is the operator saying "this exact document", and silently
            // ignoring that would be worse than refusing it.
            verifyDigest(location, body, expectedSha256);
            return new String(body, StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new PacException("PAC file not found: " + location);
        } catch (IOException unreadable) {
            throw new PacException("Could not read PAC file " + location + ": "
                    + unreadable, unreadable);
        }
    }

    /**
     * @param expectedSha256 hex SHA-256, case and whitespace insensitive; null skips the check
     * @throws PacException if the document does not have that digest, or the value is not a
     *         SHA-256 at all -- a malformed pin can never match, and accepting it would leave
     *         the operator believing the document is checked when nothing could ever pass
     */
    private static void verifyDigest(String location, byte[] body, String expectedSha256) {
        if (expectedSha256 == null) {
            return;
        }
        String expected = requireWellFormedDigest(expectedSha256);
        String actual = sha256Hex(body);
        if (!actual.equals(expected)) {
            throw new PacException("PAC file " + location + " does not match --pac-local-sha256."
                    + " Expected " + expected + ", got " + actual
                    + ". Refusing to use it: the document decides where traffic and upstream"
                    + " proxy credentials are sent.");
        }
    }

    /**
     * @return the normalised digest, or null when none was configured
     * @throws PacException if it is not a SHA-256 at all -- a malformed pin can never match, and
     *         accepting it would leave the operator believing the document is checked when
     *         nothing could ever pass
     */
    private static String requireWellFormedDigest(String expectedSha256) {
        if (expectedSha256 == null) {
            return null;
        }
        String expected = expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new PacException("--pac-local-sha256 must be 64 hex characters (a SHA-256), "
                    + "got: " + expectedSha256);
        }
        return expected;
    }

    static String sha256Hex(byte[] body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // Failing closed is the only safe answer: the alternative is treating an unverified
            // document as verified.
            throw new PacException("SHA-256 unavailable, cannot verify the PAC file", impossible);
        }
    }

    /**
     * @return where {@code host} should be reached, or null when the file could not be
     *         evaluated -- see {@link #resolveOrNull} for why that is not DIRECT
     */
    public PacResult resolve(String url, String host) {
        PacResult result = resolveOrNull(url, host);
        return result == null ? PacResult.direct() : result;
    }

    /**
     * As {@link #resolve}, but says when it does not know.
     *
     * <p>A failed evaluation used to become {@link PacResult#direct()}. That is not a neutral
     * default: on a network whose only sanctioned egress is a proxy, it takes traffic the
     * operator routed deliberately and sends it straight out instead -- and it did so past a
     * configured {@code --proxy} as well, which no reading of "fallback" covers. The component
     * whose entire job is deciding where traffic goes was failing open.
     *
     * <p>So the callers are told, and each falls back to the static {@code --proxy} when there
     * is one. Going direct remains the answer only when nothing else was configured, because
     * then there is genuinely nowhere else to send it.
     *
     * @return null when the file threw; a real result otherwise
     */
    public PacResult resolveOrNull(String url, String host) {
        if (host == null) {
            return PacResult.direct();
        }
        long now = clock.getAsLong();
        // Keyed by the whole evaluation input, not by host. FindProxyForURL is handed url and
        // host, and a PAC file may branch on either; caching by host alone let the first
        // decision for a host stand in for every other, so a client could choose which one
        // applied by arranging which request arrived first. HTTP and WebSocket callers supply
        // the full URL; CONNECT only exposes an authority and uses a synthetic HTTPS URL.
        String key = cacheKey(url, host);
        CachedResult cached = cache.get(key);
        if (cached != null && now - cached.decidedAtMs() < CACHE_TTL_MS) {
            return cached.result();
        }
        PacResult result;
        try {
            result = PacResult.parse(interpreter.findProxyForUrl(url, host));
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING,
                    "PAC evaluation failed for {0} ({1}); falling back to the configured proxy, "
                    + "or direct if there is none",
                    new Object[]{host, failure.getMessage()});
            // Deliberately not cached. A failure is usually about this evaluation -- a
            // dnsResolve that timed out, say -- and caching it would hold the fallback route in
            // place for a minute after the condition passed.
            return null;
        }
        if (result.getEntries().size() > 1) {
            // Said once per destination rather than silently: the list is a failover list, and
            // only the first entry is used. An operator who wrote "PROXY a; PROXY b" is
            // entitled to know that b will never be tried rather than discovering it during an
            // outage of a.
            LOG.log(Level.INFO,
                    "PAC returned {0} directives for {1}; using {2}. Failover to the remaining "
                    + "entries is not implemented.",
                    new Object[]{result.getEntries().size(), host, result.first()});
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new CachedResult(result, now));
        return result;
    }

    /**
     * The cache key: both arguments the interpreter is given.
     *
     * <p>{@code url} alone would be enough for every current caller, since each builds it from
     * the host it is about to reach. Including the host as well costs nothing and keeps the key
     * honest if a caller ever passes a url whose authority differs from the host it asks about.
     */
    private static String cacheKey(String url, String host) {
        return (url == null ? "" : url) + ' ' + host;
    }

    /** Evaluates without consulting or filling the cache; used by --pac-test. */
    public PacResult evaluateUncached(String url, String host) {
        return PacResult.parse(interpreter.findProxyForUrl(url, host));
    }

    public String getSource() {
        return source;
    }

    int cacheSize() {
        return cache.size();
    }
}
