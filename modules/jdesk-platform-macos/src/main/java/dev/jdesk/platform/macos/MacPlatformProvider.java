package dev.jdesk.platform.macos;

import dev.jdesk.api.PlatformInfo;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;

/** macOS adapter: AppKit windows + WKWebView through FFM (public APIs only). */
public final class MacPlatformProvider implements PlatformProvider {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public MacPlatformProvider() {
    }

    @Override
    public String id() {
        return "macos-wkwebview";
    }

    @Override
    public PlatformInfo info() {
        return new PlatformInfo(
                System.getProperty("os.name", "Mac OS X"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"));
    }

    @Override
    public PlatformApplication createApplication(PlatformApplicationConfig config) {
        return new MacPlatformApplication(config);
    }
}
