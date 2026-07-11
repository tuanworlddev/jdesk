/** Test support: evidence writer and verifier. Never part of production runtime variants. */
module dev.jdesk.testkit {
    requires transitive dev.jdesk.api;
    requires transitive dev.jdesk.webview.spi;
    requires com.fasterxml.jackson.databind;
    exports dev.jdesk.testkit.evidence;
}
