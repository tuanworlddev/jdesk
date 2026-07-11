package dev.jdesk.platform.windows;

import dev.jdesk.api.PlatformInfo;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;

/** Windows adapter: Win32 windows + WebView2 (Evergreen) through FFM. */
public final class WindowsPlatformProvider implements PlatformProvider {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public WindowsPlatformProvider() {
    }

    @Override
    public String id() {
        return "windows-webview2";
    }

    @Override
    public PlatformInfo info() {
        return new PlatformInfo(
                System.getProperty("os.name", "Windows"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"));
    }

    @Override
    public PlatformApplication createApplication(PlatformApplicationConfig config) {
        return new WindowsPlatformApplication(config);
    }
}
