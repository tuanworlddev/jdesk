package dev.jdesk.webview.spi;

import java.net.URI;
import java.util.Objects;

/**
 * @param uri full request URI, e.g. {@code jdesk://app/index.html}
 * @param method HTTP-style method reported by the engine; only GET/HEAD are served
 */
public record AssetRequest(URI uri, String method) {
    public AssetRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(method, "method");
    }
}
