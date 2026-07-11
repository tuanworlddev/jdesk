/** Runtime core. Must contain no Win32, AppKit, GTK, WebKit, or WebView2 classes. */
module dev.jdesk.runtime {
    requires transitive dev.jdesk.api;
    requires transitive dev.jdesk.webview.spi;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires dev.jdesk.instance;
    requires jdk.httpserver; // opt-in automation endpoint (-Djdesk.automation=true)

    exports dev.jdesk.runtime.json;
    exports dev.jdesk.runtime.config;
    exports dev.jdesk.runtime.assets to
            dev.jdesk.testapps.nativesmoke, dev.jdesk.testapps.securityprobe;
    exports dev.jdesk.runtime.boot to
            dev.jdesk.testapps.nativesmoke, dev.jdesk.testapps.securityprobe;
    exports dev.jdesk.runtime.ipc to
            dev.jdesk.testapps.nativesmoke, dev.jdesk.testapps.securityprobe;
    opens dev.jdesk.runtime.ipc to com.fasterxml.jackson.databind;
    // Automation endpoint DTOs (EvaluateRequest/ConsoleLine JSON binding).
    opens dev.jdesk.runtime.boot to com.fasterxml.jackson.databind;

    uses dev.jdesk.webview.spi.PlatformProvider;
    provides dev.jdesk.api.JDeskBootstrap with dev.jdesk.runtime.boot.RuntimeBootstrap;
}
