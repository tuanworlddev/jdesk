/** Linux adapter: GTK 3 + WebKitGTK 4.1 via FFM. Requires native access. */
module dev.jdesk.platform.linux {
    requires dev.jdesk.webview.spi;
    requires dev.jdesk.ffm;
    provides dev.jdesk.webview.spi.PlatformProvider
            with dev.jdesk.platform.linux.LinuxPlatformProvider;
}
