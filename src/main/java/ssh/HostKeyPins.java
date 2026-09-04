package ssh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The host keys the tunnel server is allowed to present, as OpenSSH SHA-256 fingerprints.
 *
 * <p>The control connection authenticates with the account key and secret as SSH username and
 * password, so the server's identity decides who receives them. Without a pin there is nothing
 * to check the server against: whoever answers on the tunnel port is handed credentials that
 * grant full API and account access.
 *
 * <p>Only SHA-256 is accepted. MD5 fingerprints -- still what {@code ssh-keygen -l} printed by
 * default for years, and what JSch's own {@code getFingerPrint} returns -- are collision-prone,
 * and a pin an attacker can collide is a pin that reads as protection while providing none.
 * They are refused with a message saying so rather than accepted and quietly trusted.
 */
public final class HostKeyPins {

    private static final String PREFIX = "SHA256:";

    private final Set<String> fingerprints;

    private HostKeyPins(Set<String> fingerprints) {
        this.fingerprints = fingerprints;
    }

    /** No pins configured: nothing can be verified. */
    public static HostKeyPins none() {
        return new HostKeyPins(Collections.emptySet());
    }

    /**
     * @param values OpenSSH SHA-256 fingerprints, with or without the {@code SHA256:} prefix
     * @throws IllegalArgumentException if any entry is not a SHA-256 fingerprint
     */
    public static HostKeyPins of(List<String> values) {
        Set<String> parsed = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            parsed.add(normalise(value.trim()));
        }
        return new HostKeyPins(parsed);
    }

    /** Convenience for a comma-separated list, the shape a command line supplies. */
    public static HostKeyPins parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
            return none();
        }
        List<String> values = new ArrayList<>();
        for (String each : commaSeparated.split(",")) {
            values.add(each);
        }
        return of(values);
    }

    private static String normalise(String value) {
        String body = value;
        if (body.toUpperCase(Locale.ROOT).startsWith("MD5:") || looksLikeMd5(body)) {
            throw new IllegalArgumentException(
                "Host key fingerprint '" + value + "' looks like MD5. Only SHA-256 fingerprints "
                    + "are accepted -- get one with: ssh-keygen -lf <key> -E sha256");
        }
        if (body.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            body = body.substring(PREFIX.length());
        }
        // OpenSSH prints base64 without padding; tolerate it either way, then check the length
        // the digest actually has, so a truncated pin is refused rather than never matching.
        String unpadded = body.endsWith("=") ? body.replaceAll("=+$", "") : body;
        if (unpadded.length() != 43) {
            throw new IllegalArgumentException(
                "Host key fingerprint '" + value + "' is not a SHA-256 fingerprint "
                    + "(expected 43 base64 characters, got " + unpadded.length() + ")");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(unpadded + "=");
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Host key fingerprint '" + value + "' is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException(
                "Host key fingerprint '" + value + "' does not decode to a 32-byte SHA-256");
        }
        // Re-encoded, not stored as written. The last base64 character of a 43-character
        // fingerprint carries two bits that no byte of the digest uses, and the decoder ignores
        // them -- so "...SIE" and "...SIF" decode to identical bytes. Comparing the strings, one
        // of those would have been accepted here and then never matched anything, which is the
        // "accepted and never matches" outcome this class refuses everywhere else.
        return Base64.getEncoder().withoutPadding().encodeToString(raw);
    }

    /** MD5 fingerprints are 16 hex pairs separated by colons. */
    private static boolean looksLikeMd5(String value) {
        return value.matches("(?i)([0-9a-f]{2}:){15}[0-9a-f]{2}");
    }

    public boolean isEmpty() {
        return fingerprints.isEmpty();
    }

    public int size() {
        return fingerprints.size();
    }

    /** The pins, normalised and {@code SHA256:}-prefixed, for logging and {@code --doctor}. */
    public List<String> displayValues() {
        List<String> out = new ArrayList<>();
        for (String each : fingerprints) {
            out.add(PREFIX + each);
        }
        return out;
    }

    /** @return true when {@code keyBlob} -- the wire-format public key -- matches a pin */
    public boolean matches(byte[] keyBlob) {
        if (keyBlob == null || fingerprints.isEmpty()) {
            return false;
        }
        return fingerprints.contains(fingerprint(keyBlob));
    }

    /** The OpenSSH SHA-256 fingerprint of {@code keyBlob}, without the {@code SHA256:} prefix. */
    static String fingerprint(byte[] keyBlob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBlob);
            return Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM this runs on ships SHA-256; if one did not, failing closed is the only
            // safe answer, since the alternative is treating an unverifiable key as verified.
            throw new IllegalStateException("SHA-256 unavailable, cannot verify host keys", ex);
        }
    }

    /** The fingerprint as it would be displayed, for an error message naming what was offered. */
    public static String displayFingerprint(byte[] keyBlob) {
        return PREFIX + fingerprint(keyBlob);
    }
}
