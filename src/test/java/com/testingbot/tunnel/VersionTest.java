package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version comparison, which used to be {@code Float} arithmetic.
 *
 * <p>The float was not merely imprecise, it was wrong in ways that would have appeared on this
 * project's own next few releases, and each failure mode is asserted here directly.
 */
class VersionTest {

    private static Version v(String text) {
        return Version.parse(text);
    }

    @Test
    void aTenthMinorReleaseIsNewerThanANinth() {
        // The float bug that mattered most: 5.10 parses to 5.1f, which is less than 5.9f. The
        // upgrade notice would have stopped appearing at exactly the release it was needed for.
        assertThat(v("5.9").isOlderThan(v("5.10"))).isTrue();
        assertThat(v("5.10").isOlderThan(v("5.9"))).isFalse();
        assertThat(v("5.10")).isGreaterThan(v("5.9"));
    }

    @Test
    void threeComponentVersionsAreUnderstood() {
        // Float.parseFloat("5.0.1") throws. The old code caught that and fell back to 0.0, so a
        // patch release made the client believe it was older than everything.
        assertThat(v("5.0.1")).isNotNull();
        assertThat(v("5.0").isOlderThan(v("5.0.1"))).isTrue();
        assertThat(v("5.0.1").isOlderThan(v("5.0.2"))).isTrue();
        assertThat(v("5.0.2").isOlderThan(v("5.0.1"))).isFalse();
    }

    @Test
    void trailingZerosDoNotChangeTheVersion() {
        assertThat(v("5.1")).isEqualTo(v("5.1.0"));
        assertThat(v("5")).isEqualTo(v("5.0.0"));
        assertThat(v("5.1").isOlderThan(v("5.1.0"))).isFalse();
        // Equal versions must agree on their hash, or a Set of them would hold both.
        assertThat(v("5.1")).hasSameHashCodeAs(v("5.1.0"));
    }

    @Test
    void aSnapshotPrecedesTheReleaseItLeadsTo() {
        // So a developer running 5.1.0-SNAPSHOT is correctly told that 5.1.0 is out.
        assertThat(v("5.1.0-SNAPSHOT").isOlderThan(v("5.1.0"))).isTrue();
        assertThat(v("5.1.0").isOlderThan(v("5.1.0-SNAPSHOT"))).isFalse();
        // But a snapshot of the next version is still newer than the current release.
        assertThat(v("5.1.0").isOlderThan(v("5.2.0-SNAPSHOT"))).isTrue();
    }

    @Test
    void preReleasesOfTheSameVersionAreOrdered() {
        assertThat(v("5.1.0-rc.1").isOlderThan(v("5.1.0-rc.2"))).isTrue();
        assertThat(v("5.1.0-rc.1")).isEqualTo(v("5.1.0-rc.1"));
    }

    @Test
    void majorVersionsDominate() {
        assertThat(v("5.99.99").isOlderThan(v("6.0"))).isTrue();
        assertThat(v("10.0").isOlderThan(v("9.0"))).isFalse();
        assertThat(v("9.0").isOlderThan(v("10.0"))).isTrue();
    }

    @Test
    void somethingUnreadableIsNullRatherThanZero() {
        // The distinction the old code could not make. Falling back to 0.0 does not mean
        // "unknown", it means "older than every release", so an unreadable version turned the
        // upgrade notice on permanently. Null lets the caller decline to guess.
        assertThat(Version.parse(null)).isNull();
        assertThat(Version.parse("")).isNull();
        assertThat(Version.parse("   ")).isNull();
        assertThat(Version.parse("unknown")).isNull();
        assertThat(Version.parse("-SNAPSHOT")).isNull();
    }

    @Test
    void parsingStopsAtTheFirstNonNumericComponent() {
        // "5.1.x.3": the 3 does not mean what its position would suggest once x is dropped, so
        // it is not read at all.
        assertThat(v("5.1.x.3")).isEqualTo(v("5.1"));
    }

    @Test
    void theVersionPrintsAsItWasWritten() {
        // This is what reaches users and the tunnel_info metric label, so it must not be
        // normalised into something they did not ship.
        assertThat(v("5.0").toString()).isEqualTo("5.0");
        assertThat(v("5.10.2").toString()).isEqualTo("5.10.2");
        assertThat(v("5.1.0-SNAPSHOT").toString()).isEqualTo("5.1.0-SNAPSHOT");
    }

    @Test
    void theProjectsOwnVersionParses() {
        // Guards the wiring, not the parser: if version.properties ever takes a shape Version
        // cannot read, App.RELEASE is null and the upgrade check silently stops working.
        assertThat(App.VERSION).isNotBlank();
        assertThat(App.RELEASE)
                .as("App.VERSION (%s) must be comparable, or the upgrade notice never fires",
                        App.VERSION)
                .isNotNull();
    }
}
