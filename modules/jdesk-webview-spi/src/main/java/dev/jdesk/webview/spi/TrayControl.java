package dev.jdesk.webview.spi;

/**
 * Platform handle for one installed tray / status-bar item. The runtime marshals these calls
 * to the UI thread and wraps this in the public {@code TrayHandle}.
 */
public interface TrayControl {

    /** Updates the item's status-bar title (UI thread). */
    void setTitle(String title);

    /** Removes the item (UI thread); idempotent. */
    void remove();
}
