package dev.jdesk.webview.spi;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Response for one asset request. The body supplier is invoked at most once; platform
 * adapters stream it to the engine and close it, including on cancellation. Error pages
 * are deterministic and never leak filesystem paths.
 *
 * @param status 200, 404, or 500
 * @param headers response headers including Content-Type; already includes cache and
 *        security headers supplied by runtime configuration
 * @param contentLength length in bytes when known, else -1
 * @param body streaming body; empty stream for error-less bodies is allowed
 */
public record AssetResponse(
        int status,
        Map<String, String> headers,
        long contentLength,
        Supplier<InputStream> body) {

    public AssetResponse {
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        Objects.requireNonNull(body, "body");
        if (status != 200 && status != 404 && status != 500) {
            throw new IllegalArgumentException("Unsupported asset status: " + status);
        }
    }
}
