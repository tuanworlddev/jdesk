/** macOS adapter: AppKit + WKWebView via FFM. Requires native access. */
module dev.jdesk.platform.macos {
    requires dev.jdesk.webview.spi;
    requires dev.jdesk.ffm;
    provides dev.jdesk.webview.spi.PlatformProvider
            with dev.jdesk.platform.macos.MacPlatformProvider;
}
