package dev.jdesk.api;

/**
 * A running file watch from {@link ApplicationHandle#watchFiles}. Closing it stops
 * delivery and releases native resources; close is idempotent and safe from any thread.
 * All active handles are also closed automatically at application shutdown.
 */
public interface FileWatchHandle extends AutoCloseable {

    /** @return true until this watch is closed (by the app or at shutdown). */
    boolean isActive();

    @Override
    void close();
}
