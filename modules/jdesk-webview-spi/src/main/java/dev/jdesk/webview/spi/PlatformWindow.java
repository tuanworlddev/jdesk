package dev.jdesk.webview.spi;

import dev.jdesk.api.Subscription;
import dev.jdesk.api.WindowId;
import java.util.function.BooleanSupplier;

/** One native window. UI-thread only. Close is idempotent. */
public interface PlatformWindow extends AutoCloseable {
    WindowId id();

    PlatformWebView webView();

    /**
     * User/OS close request (e.g. WM_CLOSE, windowShouldClose). The handler returns
     * false to veto. Called on the UI thread.
     */
    Subscription onCloseRequested(BooleanSupplier handler);

    /** Fires exactly once when the native window has been destroyed. UI thread. */
    Subscription onClosed(Runnable handler);

    void show();

    void hide();

    void focus();

    void setMinimized(boolean minimized);

    void setMaximized(boolean maximized);

    void setFullscreen(boolean fullscreen);

    void setAlwaysOnTop(boolean alwaysOnTop);

    void setTitle(String title);

    void setBounds(WindowBounds bounds);

    /** Current bounds in the same coordinate convention {@link #setBounds} accepts. */
    WindowBounds getBounds();

    /** Opens the OS print dialog for this window's page content. Default: unsupported. */
    default void print() {
        throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                "Printing is not supported by this platform adapter");
    }

    /**
     * Pops up a native context menu modally (UI thread) and returns the chosen action id, or
     * empty. Default: empty (no native context menu).
     */
    default java.util.Optional<String> showContextMenu(dev.jdesk.api.MenuSpec menu) {
        return java.util.Optional.empty();
    }

    /**
     * Registers an OS file-drop listener (UI thread); returns an unsubscribe action. Default:
     * a no-op unsubscribe (no native file-drop).
     */
    default Runnable onFileDrop(
            java.util.function.Consumer<java.util.List<java.nio.file.Path>> listener) {
        return () -> { };
    }

    /**
     * Registers a window focus-change listener (UI thread): {@code true} when the window becomes
     * key/active, {@code false} when it resigns. Returns an unsubscribe action. Default: no-op.
     */
    default Runnable onFocusChanged(java.util.function.Consumer<Boolean> listener) {
        return () -> { };
    }

    @Override
    void close();
}
