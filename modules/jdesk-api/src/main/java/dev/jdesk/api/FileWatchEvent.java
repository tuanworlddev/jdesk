package dev.jdesk.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One filesystem change under a watched root (see
 * {@link ApplicationHandle#watchFiles}). {@code path} is absolute. {@code OVERFLOW} means
 * the OS coalesced or dropped events and the app should rescan the subtree; its
 * {@code path} is the watch root.
 */
public record FileWatchEvent(Path path, Kind kind) {

    public enum Kind {
        /** A file or directory appeared. */
        CREATED,
        /** Contents or metadata changed. */
        MODIFIED,
        /** A file or directory was removed (or renamed away). */
        DELETED,
        /** Events were dropped/coalesced; {@code path} is the root and a rescan is needed. */
        OVERFLOW
    }

    public FileWatchEvent {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }

    /** An overflow marker rooted at {@code root}. */
    public static FileWatchEvent overflow(Path root) {
        return new FileWatchEvent(root, Kind.OVERFLOW);
    }
}
