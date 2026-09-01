package com.testingbot.tunnel;

import java.lang.reflect.Method;

/**
 * The jar's entry point, and the only class in it that an old JVM can load.
 *
 * <p>{@link App#checkJavaVersion()} prints a clear message about needing Java 17, and on the JVMs
 * it was written for it never ran: the rest of the jar is compiled to class file version 61, so
 * loading {@code App} fails before {@code main} is reached. What a user actually saw was
 *
 * <pre>
 * Java 11: Error: LinkageError occurred while loading main class com.testingbot.tunnel.App
 * Java 8:  Error: A JNI error has occurred, please check your installation and try again
 * </pre>
 *
 * <p>The second is worse than unhelpful -- it points at the installation being broken, when the
 * installation is fine and merely old, and neither mentions Java 17.
 *
 * <p>So this class is compiled for an older release than the rest of the jar (see the
 * {@code launcher-compat} execution in the pom) and named as {@code Main-Class}. It checks the
 * version first and only then reaches {@code App}, through reflection so that resolving it
 * cannot happen before the check.
 *
 * <p>Deliberately plain Java: no generics, no switch expressions, nothing that would stop it
 * compiling to the old release. Nothing here should ever need to change.
 */
public final class Launcher {

    /** Kept in step with {@code App.MINIMUM_JAVA_VERSION}; duplicated because App cannot load. */
    private static final int MINIMUM_JAVA_VERSION = 17;

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        int major = majorVersion();
        if (major > 0 && major < MINIMUM_JAVA_VERSION) {
            System.err.println("TestingBot Tunnel requires Java " + MINIMUM_JAVA_VERSION
                    + " or higher, but this is Java " + major + ".");
            System.err.println("Java itself is fine -- it is just older than this tunnel needs.");
            System.err.println("Install a newer Java, or run the Docker image instead:");
            System.err.println("  docker run testingbot/tunnel");
            System.exit(1);
        }
        // Reflection so that App, which an old JVM cannot load, is not resolved until the check
        // above has passed.
        Class<?> app = Class.forName("com.testingbot.tunnel.App");
        Method main = app.getMethod("main", String[].class);
        main.invoke(null, new Object[]{args});
    }

    /**
     * The feature version, or 0 when it cannot be determined.
     *
     * <p>Parsed from {@code java.version} rather than {@code Runtime.version()}, which does not
     * exist before Java 9 -- the versions this class exists to talk to.
     */
    static int majorVersion() {
        String version = System.getProperty("java.version");
        if (version == null || version.length() == 0) {
            return 0;
        }
        // "1.8.0_432" is Java 8; "11.0.27" and "17.0.17" say so directly.
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException unparseable) {
            return 0;
        }
    }
}
