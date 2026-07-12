package dev.jdesk.api;

/**
 * A live system tray / status-bar item from {@link ApplicationHandle#createTrayItem}.
 * Closing removes it; close is idempotent. All tray items are removed at shutdown.
 */
public interface TrayHandle extends AutoCloseable {

    /** Updates the item's status-bar title. */
    void setTitle(String title);

    /**
     * Replaces the item's click menu — e.g. to reflect a toggled option as a checkmark or to
     * enable/disable entries. Takes effect the next time the menu is shown.
     */
    void setMenu(MenuSpec menu);

    /** Removes the tray item. */
    @Override
    void close();
}
