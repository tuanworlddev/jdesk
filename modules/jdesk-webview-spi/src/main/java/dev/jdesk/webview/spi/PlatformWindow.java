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

    @Override
    void close();
}
