package dev.jdesk.updater;

import java.nio.file.Path;
import java.util.Objects;

/** Version selected for launch and whether an unconfirmed update was rolled back. */
public record UpdateLaunch(String version, Path packagePath, boolean rolledBack) {
    public UpdateLaunch {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        packagePath = Objects.requireNonNull(packagePath, "packagePath")
                .toAbsolutePath().normalize();
    }
}
