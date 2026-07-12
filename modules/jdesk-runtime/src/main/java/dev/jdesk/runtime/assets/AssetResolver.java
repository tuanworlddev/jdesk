package dev.jdesk.runtime.assets;

import dev.jdesk.api.AssetRoute;
import dev.jdesk.webview.spi.AssetHandler;
import dev.jdesk.webview.spi.AssetRequest;
import dev.jdesk.webview.spi.AssetResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Production {@code jdesk://app/} resolver (spec section 9.1). No sockets, no HTTP
 * listener: platform adapters call {@link #handle(AssetRequest)} from their documented
 * scheme-interception APIs. Responses stream; error pages are deterministic and never
 * leak filesystem paths.
 */
public final class AssetResolver implements AssetHandler {
    private static final Logger LOG = System.getLogger(AssetResolver.class.getName());
    /** Content-hashed asset names like {@code app.3f9d2c1a.js} or {@code chunk-BX92KD01.js}. */
    private static final Pattern HASHED_NAME =
            Pattern.compile(".*[.-][0-9a-zA-Z_-]{8,}\\.[a-z0-9]+$");

    private static final String NOT_FOUND_BODY =
            "<!doctype html><html><head><title>Not found</title></head>"
                    + "<body><h1>404 Not Found</h1></body></html>";
    private static final String ERROR_BODY =
            "<!doctype html><html><head><title>Error</title></head>"
                    + "<body><h1>500 Internal Error</h1></body></html>";
    private static final String METHOD_NOT_ALLOWED_BODY =
            "<!doctype html><html><head><title>Method not allowed</title></head>"
                    + "<body><h1>405 Method Not Allowed</h1></body></html>";
    private static final String PAYLOAD_TOO_LARGE_BODY =
            "<!doctype html><html><head><title>Payload too large</title></head>"
                    + "<body><h1>413 Payload Too Large</h1></body></html>";

    private final AssetSource source;
    private final boolean spaFallback;
    private final Map<String, String> securityHeaders;
    private final long maxUploadBytes;
    /** App-defined routes, longest prefix first so nested prefixes win. */
    private final List<Map.Entry<String, AssetRoute>> routes;

    public AssetResolver(AssetSource source, boolean spaFallback, Map<String, String> securityHeaders) {
        this(source, spaFallback, securityHeaders, Map.of());
    }

    public AssetResolver(AssetSource source, boolean spaFallback,
            Map<String, String> securityHeaders, Map<String, AssetRoute> assetRoutes) {
        this(source, spaFallback, securityHeaders, assetRoutes, AssetRequest.MAX_BODY_BYTES);
    }

    /**
     * @param spaFallback serve {@code index.html} for extension-less misses; explicit
     *        opt-in for SPA routing
     * @param securityHeaders CSP and friends from runtime configuration; added to every
     *        response
     * @param assetRoutes app-defined routes keyed by path prefix (checked before the
     *        asset source; a route owns everything under its prefix)
     * @param maxUploadBytes largest POST body handed to a route before a 413; the platform
     *        adapter reads at most this many + 1 so oversize is caught without buffering
     */
    public AssetResolver(AssetSource source, boolean spaFallback,
            Map<String, String> securityHeaders, Map<String, AssetRoute> assetRoutes,
            long maxUploadBytes) {
        this.source = source;
        this.spaFallback = spaFallback;
        this.securityHeaders = Map.copyOf(securityHeaders);
        this.maxUploadBytes = maxUploadBytes;
        this.routes = assetRoutes.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, AssetRoute> e)
                        -> e.getKey().length()).reversed())
                .toList();
    }

    @Override
    public AssetResponse handle(AssetRequest request) {
        try {
            String method = request.method();
            boolean getOrHead = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
            boolean post = "POST".equalsIgnoreCase(method);
            if (!getOrHead && !post) {
                return methodNotAllowed(); // PUT/DELETE/PATCH/... are not part of the asset ABI
            }
            if (!"jdesk".equalsIgnoreCase(request.uri().getScheme())
                    || !"app".equalsIgnoreCase(request.uri().getAuthority())) {
                return notFound();
            }
            if (post && request.body().length > maxUploadBytes) {
                return payloadTooLarge(); // reject before invoking any app route
            }
            Optional<String> normalized = AssetPaths.normalize(request.uri().getRawPath());
            if (normalized.isEmpty()) {
                return notFound(); // rejected path: deterministic 404, no echo of input
            }
            String path = normalized.get().isEmpty() ? "index.html" : normalized.get();
            AssetResponse routed = tryRoutes(path, request);
            if (routed != null) {
                return routed;
            }
            if (post) {
                // A POST only makes sense against an app route; the static source is
                // read-only, so an unmatched write is a plain 404 (no method probing).
                return notFound();
            }
            Optional<AssetSource.Asset> asset = source.find(path);
            if (asset.isEmpty() && spaFallback && !path.contains(".")) {
                path = "index.html";
                asset = source.find(path);
            }
            if (asset.isEmpty()) {
                return notFound();
            }
            Map<String, String> headers = new HashMap<>(securityHeaders);
            headers.put("Content-Type", MimeTypes.forPath(path));
            headers.put("Cache-Control", cacheControlFor(path));
            return respond(request, headers, asset.get());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.ERROR, "Asset resolution failed", e);
            return serverError();
        }
    }

    /** Serves {@code path} from an app-defined route, or returns null when none matches. */
    private AssetResponse tryRoutes(String path, AssetRequest request) throws IOException {
        for (Map.Entry<String, AssetRoute> entry : routes) {
            String prefix = entry.getKey();
            if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
                continue;
            }
            String subPath = path.equals(prefix) ? "" : path.substring(prefix.length() + 1);
            Optional<AssetRoute.Response> served = entry.getValue()
                    .serve(new AssetRoute.Request(
                            subPath, request.method(), request.body(), request.headers()));
            if (served.isEmpty()) {
                return notFound();
            }
            AssetRoute.Response response = served.get();
            Map<String, String> headers = new HashMap<>(securityHeaders);
            headers.put("Content-Type", response.contentType());
            headers.put("Cache-Control", "no-cache");
            headers.putAll(response.headers());
            AssetSource.Asset asset =
                    new AssetSource.Asset(response.contentLength(), response.body()::get);
            return respond(request, headers, asset);
        }
        return null;
    }

    /** Success path shared by source assets and routes: Accept-Ranges + Range/206/416. */
    private AssetResponse respond(AssetRequest request, Map<String, String> headers,
            AssetSource.Asset found) {
        headers.put("Accept-Ranges", "bytes");
        Optional<String> range = request.header("range");
        if (range.isPresent() && found.size() >= 0) {
            ByteRanges.Result parsed = ByteRanges.parse(range.get(), found.size());
            if (parsed.kind() == ByteRanges.Kind.UNSATISFIABLE) {
                return rangeNotSatisfiable(found.size());
            }
            if (parsed.kind() == ByteRanges.Kind.PARTIAL) {
                headers.put("Content-Range",
                        "bytes " + parsed.start() + "-" + parsed.endInclusive()
                                + "/" + found.size());
                return new AssetResponse(206, headers, parsed.length(),
                        () -> openSliceQuietly(found, parsed.start(), parsed.length()));
            }
        }
        return new AssetResponse(200, headers, found.size(), () -> openQuietly(found));
    }

    private static InputStream openQuietly(AssetSource.Asset asset) {
        try {
            return asset.open().open();
        } catch (IOException e) {
            LOG.log(Level.ERROR, "Asset stream open failed", e);
            return InputStream.nullInputStream();
        }
    }

    private static InputStream openSliceQuietly(AssetSource.Asset asset, long offset, long length) {
        try {
            return new LimitedInputStream(asset.open().openAt(offset), length);
        } catch (IOException e) {
            LOG.log(Level.ERROR, "Asset stream open failed", e);
            return InputStream.nullInputStream();
        }
    }

    private AssetResponse rangeNotSatisfiable(long totalSize) {
        Map<String, String> headers = new HashMap<>(securityHeaders);
        headers.put("Content-Type", "text/plain; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        headers.put("Content-Range", "bytes */" + totalSize);
        return new AssetResponse(416, headers, 0, InputStream::nullInputStream);
    }

    private String cacheControlFor(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return "no-cache";
        }
        if (HASHED_NAME.matcher(path).matches()) {
            return "public, max-age=31536000, immutable";
        }
        return "no-cache";
    }

    private AssetResponse notFound() {
        byte[] body = NOT_FOUND_BODY.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>(securityHeaders);
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        return new AssetResponse(404, headers, body.length, () -> new ByteArrayInputStream(body));
    }

    private AssetResponse serverError() {
        byte[] body = ERROR_BODY.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>(securityHeaders);
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        return new AssetResponse(500, headers, body.length, () -> new ByteArrayInputStream(body));
    }

    private AssetResponse methodNotAllowed() {
        byte[] body = METHOD_NOT_ALLOWED_BODY.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>(securityHeaders);
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        headers.put("Allow", "GET, HEAD, POST");
        return new AssetResponse(405, headers, body.length, () -> new ByteArrayInputStream(body));
    }

    private AssetResponse payloadTooLarge() {
        byte[] body = PAYLOAD_TOO_LARGE_BODY.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>(securityHeaders);
        headers.put("Content-Type", "text/html; charset=utf-8");
        headers.put("Cache-Control", "no-cache");
        return new AssetResponse(413, headers, body.length, () -> new ByteArrayInputStream(body));
    }
}
