/** Windows adapter: Win32 + WebView2 via FFM. Requires native access. */
module dev.jdesk.platform.windows {
    requires dev.jdesk.webview.spi;
    requires dev.jdesk.ffm;
    provides dev.jdesk.webview.spi.PlatformProvider
            with dev.jdesk.platform.windows.WindowsPlatformProvider;
}
