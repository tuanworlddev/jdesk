package dev.jdesk.webview.spi;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * @param uri full request URI, e.g. {@code jdesk://app/index.html}
 * @param method HTTP-style method reported by the engine; only GET/HEAD are served
 * @param headers request headers reported by the engine (may be a subset; engines are
 *        only required to forward headers that affect asset resolution, e.g. Range).
 *        Keys are normalized to lower case.
 */
public record AssetRequest(URI uri, String method, Map<String, String> headers) {
    public AssetRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(headers, "headers");
        Map<String, String> normalized = new HashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            normalized.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        headers = Map.copyOf(normalized);
    }

    public AssetRequest(URI uri, String method) {
        this(uri, method, Map.of());
    }

    /** Case-insensitive request-header lookup. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }
}
