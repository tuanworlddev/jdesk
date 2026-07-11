package dev.jdesk.webview.spi;

import dev.jdesk.api.WindowId;

/** One native window. UI-thread only. Close is idempotent. */
public interface PlatformWindow extends AutoCloseable {
    WindowId id();

    PlatformWebView webView();

    void show();

    void hide();

    void setTitle(String title);

    void setBounds(WindowBounds bounds);

    @Override
    void close();
}
