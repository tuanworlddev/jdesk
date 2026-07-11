package dev.jdesk.api;

import java.util.Objects;
import java.util.Set;

/**
 * One granted capability. Empty {@code windows} means the grant applies to every window
 * of the application; a non-empty set restricts it to those window ids.
 */
public record CapabilityGrant(String capability, Set<String> windows) {
    public CapabilityGrant {
        Objects.requireNonNull(capability, "capability");
        if (capability.isBlank() || capability.length() > 128) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Invalid capability name");
        }
        windows = Set.copyOf(Objects.requireNonNull(windows, "windows"));
    }

    public static CapabilityGrant forAllWindows(String capability) {
        return new CapabilityGrant(capability, Set.of());
    }
}
