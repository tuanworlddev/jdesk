package dev.jdesk.updater;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict Semantic Version 2.0 value used for update ordering and downgrade prevention. */
public record ReleaseVersion(int major, int minor, int patch, List<String> preRelease)
        implements Comparable<ReleaseVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    public ReleaseVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version numbers must not be negative");
        }
        preRelease = List.copyOf(Objects.requireNonNull(preRelease, "preRelease"));
    }

    public static ReleaseVersion parse(String value) {
        Matcher matcher = FORMAT.matcher(Objects.requireNonNull(value, "value"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version");
        }
        List<String> identifiers = matcher.group(4) == null
                ? List.of() : List.of(matcher.group(4).split("\\."));
        for (String identifier : identifiers) {
            if (identifier.matches("[0-9]+") && identifier.length() > 1
                    && identifier.charAt(0) == '0') {
                throw new IllegalArgumentException(
                        "Numeric pre-release identifiers must not have leading zeroes");
            }
        }
        try {
            return new ReleaseVersion(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                    identifiers);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Version component is too large", e);
        }
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        int core = Integer.compare(major, other.major);
        if (core == 0) {
            core = Integer.compare(minor, other.minor);
        }
        if (core == 0) {
            core = Integer.compare(patch, other.patch);
        }
        if (core != 0) {
            return core;
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            if (preRelease.isEmpty() && other.preRelease.isEmpty()) {
                return 0;
            }
            return preRelease.isEmpty() ? 1 : -1;
        }
        int count = Math.min(preRelease.size(), other.preRelease.size());
        for (int i = 0; i < count; i++) {
            int comparison = compareIdentifier(preRelease.get(i), other.preRelease.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.matches("[0-9]+");
        boolean rightNumeric = right.matches("[0-9]+");
        if (leftNumeric && rightNumeric) {
            return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    @Override
    public String toString() {
        String result = major + "." + minor + "." + patch;
        return preRelease.isEmpty() ? result : result + "-" + String.join(".", preRelease);
    }
}
