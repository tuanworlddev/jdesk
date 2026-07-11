/** Platform SPI. Implemented by platform adapters, consumed by the runtime. */
module dev.jdesk.webview.spi {
    requires transitive dev.jdesk.api;
    exports dev.jdesk.webview.spi;
}
