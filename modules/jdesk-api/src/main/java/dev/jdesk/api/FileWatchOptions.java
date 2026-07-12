package dev.jdesk.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Options for {@link ApplicationHandle#watchFiles}.
 *
 * @param recursive watch the whole subtree under the root, not just its direct children
 * @param coalesceWindow how long the runtime batches raw events before delivering them as
 *        one list; deduplicates bursts (editors write-rename-chmod in quick succession).
 *        Zero delivers each batch as soon as the OS reports it.
 */
public record FileWatchOptions(boolean recursive, Duration coalesceWindow) {

    private static final Duration DEFAULT_COALESCE = Duration.ofMillis(15);

    /** Recursive watch of the whole subtree, default coalescing window. */
    public static final FileWatchOptions RECURSIVE = new FileWatchOptions(true, DEFAULT_COALESCE);
    /** Direct-children-only watch, default coalescing window. */
    public static final FileWatchOptions NON_RECURSIVE =
            new FileWatchOptions(false, DEFAULT_COALESCE);

    public FileWatchOptions {
        Objects.requireNonNull(coalesceWindow, "coalesceWindow");
        if (coalesceWindow.isNegative()) {
            throw new IllegalArgumentException("coalesceWindow must be >= 0");
        }
    }

    public FileWatchOptions withCoalesceWindow(Duration window) {
        return new FileWatchOptions(recursive, window);
    }
}
