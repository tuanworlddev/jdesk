package dev.jdesk.updater;

import java.nio.file.Path;
import java.util.Optional;

/** Outcome of a check-and-stage operation. */
public record UpdateResult(Status status, Optional<String> version,
        Optional<Path> activatedDirectory) {
    public enum Status {
        DISABLED,
        NO_UPDATE,
        CHANNEL_MISMATCH,
        CURRENT_VERSION_UNSUPPORTED,
        /** A newer release exists but a staged/phased rollout has not yet reached this install. */
        HELD_BACK,
        STAGED
    }

    public UpdateResult {
        version = version == null ? Optional.empty() : version;
        activatedDirectory = activatedDirectory == null
                ? Optional.empty() : activatedDirectory;
    }

    public static UpdateResult of(Status status) {
        return new UpdateResult(status, Optional.empty(), Optional.empty());
    }
}
