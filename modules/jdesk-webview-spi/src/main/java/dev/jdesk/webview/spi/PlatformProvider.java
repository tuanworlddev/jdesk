package dev.jdesk.webview.spi;

import dev.jdesk.api.PlatformInfo;

/**
 * Entry point implemented by each platform adapter and discovered through
 * {@link java.util.ServiceLoader}. A packaged application must contain exactly one provider.
 * The full SPI (application, window, WebView) lands in Phase 1.
 */
public interface PlatformProvider {
    /** Stable provider ID, e.g. {@code windows-webview2}. Never {@code fake} in native evidence. */
    String id();

    PlatformInfo info();
}
