package com.testingbot.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A dotted release version, compared component by component.
 *
 * <p>This existed as {@code Float} and the arithmetic was wrong in three separate ways, all of
 * which only appear once the project has more than nine minor releases or more than two
 * components:
 *
 * <ul>
 *   <li>{@code 5.10} parses to {@code 5.1f}, which sorts <em>below</em> {@code 5.9}. The upgrade
 *       notice would stop appearing at exactly the point there was something to upgrade to.</li>
 *   <li>{@code 5.0.1} is not a float at all. {@code Float.parseFloat} threw, the exception was
 *       caught, and the version became {@code 0.0} -- so every server version looked newer and
 *       the notice appeared on every single startup.</li>
 *   <li>Floats are binary, so equality between two versions that should match was never
 *       something to rely on.</li>
 * </ul>
 *
 * <p>Comparison is numeric per component, left to right, with a missing component read as zero,
 * so {@code 5.1} and {@code 5.1.0} are equal. A pre-release suffix ({@code 5.1.0-SNAPSHOT})
 * sorts below the same version without one, which is what semver says and what makes a local
 * snapshot correctly see the matching release as newer.
 */
public final class Version implements Comparable<Version> {

    private final List<Integer> components;
    private final String preRelease;
    private final String display;

    private Version(List<Integer> components, String preRelease, String display) {
        this.components = components;
        this.preRelease = preRelease;
        this.display = display;
    }

    /**
     * @param text something like {@code 5}, {@code 5.1}, {@code 5.10.2} or {@code 5.1-SNAPSHOT}
     * @return the parsed version, or null when there is no leading number to read -- the callers
     *         here are a startup notice and a metrics label, and neither is worth failing a
     *         tunnel over. Refusing to guess is the point: the previous code turned anything it
     *         could not read into 0.0, which is not "unknown" but "older than everything".
     */
    public static Version parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Split the pre-release suffix off first, so the numeric scan below does not have to
        // know about it: "5.1.0-SNAPSHOT" and "5.1.0-rc.1" both leave "5.1.0".
        String numeric = trimmed;
        String suffix = null;
        int dash = trimmed.indexOf('-');
        if (dash >= 0) {
            numeric = trimmed.substring(0, dash);
            suffix = trimmed.substring(dash + 1);
        }

        List<Integer> parsed = new ArrayList<>(3);
        for (String part : numeric.split("\\.", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Integer.valueOf(Integer.parseInt(part)));
            } catch (NumberFormatException notANumber) {
                // Stop at the first component that is not a number rather than dropping it and
                // carrying on: in "5.1.x.3" the 3 does not mean what its position would imply.
                break;
            }
        }
        if (parsed.isEmpty()) {
            return null;
        }
        return new Version(List.copyOf(parsed), suffix, trimmed);
    }

    @Override
    public int compareTo(Version other) {
        int width = Math.max(components.size(), other.components.size());
        for (int i = 0; i < width; i++) {
            // A missing component is zero, so 5.1 == 5.1.0 rather than one preceding the other.
            int mine = i < components.size() ? components.get(i) : 0;
            int theirs = i < other.components.size() ? other.components.get(i) : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }
        if (Objects.equals(preRelease, other.preRelease)) {
            return 0;
        }
        // A pre-release precedes the release it leads to; two different pre-releases of the same
        // version are ordered by name, which is right for rc.1 < rc.2 and arbitrary but stable
        // otherwise.
        if (preRelease == null) {
            return 1;
        }
        if (other.preRelease == null) {
            return -1;
        }
        return preRelease.compareTo(other.preRelease);
    }

    /** True when {@code other} is a release this one should be upgraded to. */
    public boolean isOlderThan(Version other) {
        return other != null && compareTo(other) < 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version && compareTo((Version) o) == 0;
    }

    @Override
    public int hashCode() {
        // Trailing zeros do not affect comparison, so they must not affect the hash either:
        // 5.1 and 5.1.0 are equal and have to agree here.
        List<Integer> trimmed = new ArrayList<>(components);
        while (trimmed.size() > 1 && trimmed.get(trimmed.size() - 1) == 0) {
            trimmed.remove(trimmed.size() - 1);
        }
        return Objects.hash(trimmed, preRelease);
    }

    /** The version as it was written, suffix and all -- this is what users and labels want. */
    @Override
    public String toString() {
        return display;
    }
}
