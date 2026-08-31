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
 * <p>Results are cached per host. PAC files are pure functions of the URL and host in all but
 * the time-dependent predicates, and re-running an interpreter for every request to the same
 * origin would be waste. The cache is bounded, because a tunnel can be pointed at a very large
 * number of hosts and an unbounded map here would be a slow leak.
 */
public final class PacPolicy {

    private static final Logger LOG = Logger.getLogger(PacPolicy.class.getName());

    /** Enough for any realistic test run; beyond this the cache is cleared rather than grown. */
    static final int MAX_CACHE_ENTRIES = 4096;

    private static final int FETCH_TIMEOUT_MS = 15_000;

    private final PacInterpreter interpreter;
    private final String source;
    private final Map<String, PacResult> cache = new ConcurrentHashMap<>();

    PacPolicy(PacInterpreter interpreter, String source) {
        this.interpreter = interpreter;
        this.source = source;
    }

    /**
     * @param location a file path, or an http(s) URL
     * @throws PacException if it cannot be read or does not parse
     */
    public static PacPolicy load(String location) {
        String text = read(location);
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

    private static String read(String location) {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(location).toURL().openConnection();
                connection.setConnectTimeout(FETCH_TIMEOUT_MS);
                connection.setReadTimeout(FETCH_TIMEOUT_MS);
                int status = connection.getResponseCode();
                if (status != 200) {
                    throw new PacException("Could not fetch PAC file " + location
                            + ": HTTP " + status);
                }
                return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
            return Files.readString(Path.of(location), StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new PacException("PAC file not found: " + location);
        } catch (IOException unreadable) {
            throw new PacException("Could not read PAC file " + location + ": "
                    + unreadable, unreadable);
        }
    }

    /**
     * @return where {@code host} should be reached, or {@link PacResult#direct()} if the file
     *         fails at runtime -- a broken PAC file must not make the tunnel unusable
     */
    public PacResult resolve(String url, String host) {
        if (host == null) {
            return PacResult.direct();
        }
        PacResult cached = cache.get(host);
        if (cached != null) {
            return cached;
        }
        PacResult result;
        try {
            result = PacResult.parse(interpreter.findProxyForUrl(url, host));
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "PAC evaluation failed for {0} ({1}); going direct",
                    new Object[]{host, failure.getMessage()});
            result = PacResult.direct();
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(host, result);
        return result;
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
