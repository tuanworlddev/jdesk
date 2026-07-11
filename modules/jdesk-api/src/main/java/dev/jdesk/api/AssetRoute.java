package dev.jdesk.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * App-defined asset route under {@code jdesk://app/<prefix>/...}: Java serves binary
 * content (proxied/cached images, generated files, thumbnails) through the streaming
 * asset pipeline instead of base64 JSON IPC. Same origin, so the strict default CSP
 * ({@code 'self'}) covers it; Range requests are answered with 206 automatically when
 * {@code contentLength} is known; the engine's HTTP cache honors your Cache-Control.
 *
 * <p>Handlers run off the UI thread — blocking I/O (disk, HTTP) is fine. Register with
 * {@link JDeskApplication.Builder#assetRoute(String, AssetRoute)}:
 *
 * <pre>{@code
 * .assetRoute("proxy/images", request -> {
 *     Path cached = imageCache.fetch(request.path()); // may block
 *     return Optional.of(AssetRoute.Response.of(cached, "image/jpeg"));
 * })
 * }</pre>
 */
@FunctionalInterface
public interface AssetRoute {

    /** @return the response, or empty for a deterministic 404. IOException maps to 500. */
    Optional<Response> serve(Request request) throws IOException;

    /**
     * @param path path below the route prefix; normalized and traversal-safe, no leading slash
     * @param headers request headers forwarded by the engine (lower-case keys; at least Range)
     */
    record Request(String path, Map<String, String> headers) {
        public Request {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(headers, "headers");
            java.util.HashMap<String, String> normalized = new java.util.HashMap<>(headers.size());
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                normalized.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
            headers = Map.copyOf(normalized);
        }

        /** Case-insensitive request-header lookup. */
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * @param contentType response Content-Type
     * @param contentLength byte length when known, else -1 (Range/206 requires it known)
     * @param body supplier of a fresh stream per call
     * @param headers extra response headers (e.g. Cache-Control: max-age); the runtime
     *        adds security headers and Content-Type/Accept-Ranges around them
     */
    record Response(String contentType, long contentLength,
            Supplier<InputStream> body, Map<String, String> headers) {
        public Response {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(body, "body");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }

        public static Response of(byte[] bytes, String contentType) {
            byte[] copy = bytes.clone();
            return new Response(contentType, copy.length,
                    () -> new ByteArrayInputStream(copy), Map.of());
        }

        public static Response of(Path file, String contentType) throws IOException {
            long size = Files.size(file);
            return new Response(contentType, size, () -> {
                try {
                    return Files.newInputStream(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, Map.of());
        }
    }
}
