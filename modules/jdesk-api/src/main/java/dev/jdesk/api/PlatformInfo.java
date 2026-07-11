package dev.jdesk.api;

import java.util.Objects;

/** Immutable description of the running platform, exposed through the public API. */
public record PlatformInfo(String osName, String osVersion, String architecture) {
    public PlatformInfo {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(osVersion, "osVersion");
        Objects.requireNonNull(architecture, "architecture");
    }
}
