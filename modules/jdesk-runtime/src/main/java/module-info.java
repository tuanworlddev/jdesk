/** Runtime core. Must contain no Win32, AppKit, GTK, WebKit, or WebView2 classes. */
module dev.jdesk.runtime {
    requires transitive dev.jdesk.api;
    requires dev.jdesk.webview.spi;
    exports dev.jdesk.runtime;
    uses dev.jdesk.webview.spi.PlatformProvider;
}
