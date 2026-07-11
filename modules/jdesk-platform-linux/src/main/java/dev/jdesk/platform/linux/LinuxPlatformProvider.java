package dev.jdesk.platform.linux;

import dev.jdesk.api.PlatformInfo;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;

/** Linux adapter: GTK 3 windows + WebKitGTK 4.1 through FFM (public APIs only). */
public final class LinuxPlatformProvider implements PlatformProvider {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public LinuxPlatformProvider() {
    }

    @Override
    public String id() {
        return "linux-webkitgtk";
    }

    @Override
    public PlatformInfo info() {
        return new PlatformInfo(
                System.getProperty("os.name", "Linux"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"));
    }

    @Override
    public PlatformApplication createApplication(PlatformApplicationConfig config) {
        return new LinuxPlatformApplication(config);
    }
}
