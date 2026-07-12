package dev.jdesk.api;

/**
 * A live system tray / status-bar item from {@link ApplicationHandle#createTrayItem}.
 * Closing removes it; close is idempotent. All tray items are removed at shutdown.
 */
public interface TrayHandle extends AutoCloseable {

    /** Updates the item's status-bar title. */
    void setTitle(String title);

    /** Removes the tray item. */
    @Override
    void close();
}
