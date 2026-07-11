package dev.jdesk.webview.spi;

/** Allow or block one navigation. Blocked navigations are logged with a correlation id. */
public enum NavigationDecision {
    ALLOW,
    BLOCK
}
