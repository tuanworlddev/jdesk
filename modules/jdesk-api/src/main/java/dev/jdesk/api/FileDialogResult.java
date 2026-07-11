package dev.jdesk.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a file open/save dialog. {@code paths} is empty when the user cancelled;
 * a save dialog yields at most one path.
 */
public record FileDialogResult(List<String> paths) {
    public FileDialogResult {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
    }

    public static FileDialogResult cancelled() {
        return new FileDialogResult(List.of());
    }

    public boolean isCancelled() {
        return paths.isEmpty();
    }

    /** The single selected path (first, for save or single-select open). */
    public Optional<String> path() {
        return paths.isEmpty() ? Optional.empty() : Optional.of(paths.get(0));
    }
}
