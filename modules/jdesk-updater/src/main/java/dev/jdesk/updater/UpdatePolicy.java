package dev.jdesk.updater;

import java.time.Duration;
import java.util.Objects;

/** Resource, channel, and downgrade policy for update network operations. */
public record UpdatePolicy(
        boolean enabled,
        UpdateChannel channel,
        boolean allowDowngrade,
        long maxPackageBytes,
        int maxManifestBytes,
        Duration connectTimeout,
        Duration requestTimeout,
        boolean allowInsecureLoopback) {
    public UpdatePolicy {
        Objects.requireNonNull(channel, "channel");
        if (maxPackageBytes < 1 || maxManifestBytes < 1) {
            throw new IllegalArgumentException("Update size limits must be positive");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
    }

    public static UpdatePolicy defaults() {
        return new UpdatePolicy(true, UpdateChannel.STABLE, false,
                512L * 1024 * 1024, 64 * 1024,
                Duration.ofSeconds(10), Duration.ofMinutes(5), false);
    }

    /**
     * Loads centrally overridable JVM properties. Invalid values fail startup instead of
     * silently weakening policy.
     */
    public static UpdatePolicy systemProperties() {
        UpdatePolicy defaults = defaults();
        return new UpdatePolicy(
                booleanProperty("jdesk.update.enabled", true),
                UpdateChannel.parse(System.getProperty("jdesk.update.channel", "stable")),
                booleanProperty("jdesk.update.allowDowngrade", false),
                longProperty("jdesk.update.maxPackageBytes", defaults.maxPackageBytes()),
                intProperty("jdesk.update.maxManifestBytes", defaults.maxManifestBytes()),
                Duration.ofMillis(longProperty("jdesk.update.connectTimeoutMs",
                        defaults.connectTimeout().toMillis())),
                Duration.ofMillis(longProperty("jdesk.update.requestTimeoutMs",
                        defaults.requestTimeout().toMillis())),
                booleanProperty("jdesk.update.allowInsecureLoopback", false));
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static long longProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static int intProperty(String name, int defaultValue) {
        long value = longProperty(name, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the integer range");
        }
        return (int) value;
    }

    private static void requirePositive(Duration value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
