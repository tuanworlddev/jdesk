/** Runtime core. Must contain no Win32, AppKit, GTK, WebKit, or WebView2 classes. */
module dev.jdesk.runtime {
    requires transitive dev.jdesk.api;
    requires transitive dev.jdesk.webview.spi;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;

    exports dev.jdesk.runtime.json;
    exports dev.jdesk.runtime.ipc;
    exports dev.jdesk.runtime.capability;
    exports dev.jdesk.runtime.assets;
    exports dev.jdesk.runtime.lifecycle;
    exports dev.jdesk.runtime.boot;

    uses dev.jdesk.webview.spi.PlatformProvider;
    provides dev.jdesk.api.JDeskBootstrap with dev.jdesk.runtime.boot.RuntimeBootstrap;
}
