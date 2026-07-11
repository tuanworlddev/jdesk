package dev.jdesk.webview.spi;

/** Decides main-frame/subframe navigations before they start. */
@FunctionalInterface
public interface NavigationListener {
    NavigationDecision onNavigate(NavigationRequest request);
}
