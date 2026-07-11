package dev.jdesk.webview.spi;

import java.net.URI;
import java.util.Objects;

/**
 * @param uri target of the navigation
 * @param mainFrame true for top-level navigations
 * @param userInitiated best-effort flag from the engine; not a security boundary
 */
public record NavigationRequest(URI uri, boolean mainFrame, boolean userInitiated) {
    public NavigationRequest {
        Objects.requireNonNull(uri, "uri");
    }
}
