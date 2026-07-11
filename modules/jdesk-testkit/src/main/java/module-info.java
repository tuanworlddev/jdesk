/** Test support: evidence writer and fixtures. Never part of production runtime variants. */
module dev.jdesk.testkit {
    requires transitive dev.jdesk.api;
    requires transitive dev.jdesk.webview.spi;
    exports dev.jdesk.testkit;
}
