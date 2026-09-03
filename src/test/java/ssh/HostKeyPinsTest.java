package ssh;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing and matching of the host key pins.
 *
 * <p>The parsing rules are the security-relevant part: a pin that is accepted but can never
 * match, or one weak enough to collide, reads as protection while providing none.
 */
class HostKeyPinsTest {

    private static final byte[] KEY = "a-public-key-blob".getBytes(StandardCharsets.UTF_8);

    private static String fingerprintOf(byte[] key) {
        return HostKeyPins.displayFingerprint(key);
    }

    @Test
    void matchesTheKeyItWasBuiltFrom() {
        HostKeyPins pins = HostKeyPins.parse(fingerprintOf(KEY));
        assertThat(pins.matches(KEY)).isTrue();
    }

    @Test
    void doesNotMatchAnyOtherKey() {
        HostKeyPins pins = HostKeyPins.parse(fingerprintOf(KEY));
        assertThat(pins.matches("a-different-key".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void acceptsTheFingerprintWithoutItsPrefix() {
        String withPrefix = fingerprintOf(KEY);
        String bare = withPrefix.substring("SHA256:".length());
        assertThat(HostKeyPins.parse(bare).matches(KEY)).isTrue();
    }

    @Test
    void acceptsSeveralPinsAndMatchesAnyOfThem() {
        byte[] other = "second-server-key".getBytes(StandardCharsets.UTF_8);
        HostKeyPins pins = HostKeyPins.parse(fingerprintOf(KEY) + "," + fingerprintOf(other));

        assertThat(pins.size()).isEqualTo(2);
        assertThat(pins.matches(KEY)).isTrue();
        assertThat(pins.matches(other)).isTrue();
    }

    @Test
    void emptyPinsMatchNothing() {
        assertThat(HostKeyPins.none().isEmpty()).isTrue();
        assertThat(HostKeyPins.none().matches(KEY)).isFalse();
        assertThat(HostKeyPins.parse("").isEmpty()).isTrue();
        assertThat(HostKeyPins.parse(null).isEmpty()).isTrue();
    }

    @Test
    void nullKeyNeverMatches() {
        assertThat(HostKeyPins.parse(fingerprintOf(KEY)).matches(null)).isFalse();
    }

    @Test
    void md5FingerprintsAreRefused() {
        assertThatThrownBy(() -> HostKeyPins.parse("MD5:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHA-256");

        // Bare, without the MD5: label -- the shape ssh-keygen printed by default for years.
        assertThatThrownBy(() -> HostKeyPins.parse("00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHA-256");
    }

    @Test
    void truncatedFingerprintIsRefusedRatherThanNeverMatching() {
        String truncated = fingerprintOf(KEY).substring(0, 20);
        assertThatThrownBy(() -> HostKeyPins.parse(truncated))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("43 base64 characters");
    }

    @Test
    void nonBase64FingerprintIsRefused() {
        assertThatThrownBy(() -> HostKeyPins.parse("SHA256:" + "!".repeat(43)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base64");
    }

    @Test
    void displayValuesArePrefixedForDoctorAndLogs() {
        HostKeyPins pins = HostKeyPins.parse(fingerprintOf(KEY));
        assertThat(pins.displayValues())
            .hasSize(1)
            .allSatisfy(value -> assertThat(value).startsWith("SHA256:"));
    }

    @Test
    void blankEntriesInAListAreIgnored() {
        HostKeyPins pins = HostKeyPins.of(List.of(fingerprintOf(KEY), "  ", ""));
        assertThat(pins.size()).isEqualTo(1);
    }
}
