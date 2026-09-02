package com.testingbot.tunnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version parsing in the one class an old JVM can load.
 *
 * <p>It reads {@code java.version} rather than {@code Runtime.version()}, because the latter
 * arrived in Java 9 and the JVMs this exists to talk to are older than that. The 1.x spelling is
 * the part worth pinning down: on Java 8 the property is "1.8.0_432", and reading the leading
 * digit would report Java 1 and refuse to run on every JVM ever made.
 */
class LauncherTest {

    private static int parse(String javaVersion) {
        String previous = System.getProperty("java.version");
        try {
            if (javaVersion == null) {
                System.clearProperty("java.version");
            } else {
                System.setProperty("java.version", javaVersion);
            }
            return Launcher.majorVersion();
        } finally {
            if (previous != null) {
                System.setProperty("java.version", previous);
            }
        }
    }

    @Test
    void theOldOneDotXSpellingIsUnderstood() {
        assertThat(parse("1.8.0_432")).isEqualTo(8);
        assertThat(parse("1.7.0_80")).isEqualTo(7);
    }

    @Test
    void modernVersionsAreRead() {
        assertThat(parse("11.0.27")).isEqualTo(11);
        assertThat(parse("17.0.17")).isEqualTo(17);
        assertThat(parse("21")).isEqualTo(21);
        assertThat(parse("23.0.1")).isEqualTo(23);
    }

    @Test
    void earlyAccessAndVendorSuffixesDoNotConfuseIt() {
        assertThat(parse("17-ea")).isEqualTo(17);
        assertThat(parse("21.0.1+12-LTS")).isEqualTo(21);
    }

    @Test
    void anUnreadableVersionYieldsZeroSoTheTunnelStillTries() {
        // Zero means "cannot tell", and the launcher then does not block startup. Refusing to
        // run because a property looked odd would be worse than letting the JVM decide.
        assertThat(parse("")).isZero();
        assertThat(parse(null)).isZero();
        assertThat(parse("banana")).isZero();
    }

    @Test
    void theMinimumMatchesTheOneAppEnforces() throws Exception {
        // Duplicated on purpose -- App cannot be loaded to ask -- so a drift would mean the
        // launcher waves through a JVM that App then rejects with the unreadable error.
        //
        // This has to read Launcher's own constant. It used to assert only that the class had
        // fields and that 17 >= App's minimum, so setting Launcher's copy to 11 left it green --
        // which is precisely the drift it exists to catch, and the reason App's constant was
        // widened to package-private in the first place.
        java.lang.reflect.Field launcherMinimum =
                Launcher.class.getDeclaredField("MINIMUM_JAVA_VERSION");
        launcherMinimum.setAccessible(true);

        assertThat(launcherMinimum.getInt(null)).isEqualTo(App.MINIMUM_JAVA_VERSION);
    }
}
