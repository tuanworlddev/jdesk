package dev.jdesk.runtime.assets;

import dev.jdesk.webview.spi.AssetHandler;
import dev.jdesk.webview.spi.AssetRequest;
import dev.jdesk.webview.spi.AssetResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    private final AssetSource source;
    private final boolean spaFallback;
    private final Map<String, String> securityHeaders;

    /**
     * @param spaFallback serve {@code index.html} for extension-less misses; explicit
     *        opt-in for SPA routing
     * @param securityHeaders CSP and friends from runtime configuration; added to every
     *        response
     */
    public AssetResolver(AssetSource source, boolean spaFallback, Map<String, String> securityHeaders) {
        this.source = source;
        this.spaFallback = spaFallback;
        this.securityHeaders = Map.copyOf(securityHeaders);
    }

    @Override
    public AssetResponse handle(AssetRequest request) {
        try {
            if (!"GET".equalsIgnoreCase(request.method()) && !"HEAD".equalsIgnoreCase(request.method())) {
                return notFound();
            }
            if (!"jdesk".equalsIgnoreCase(request.uri().getScheme())
                    || !"app".equalsIgnoreCase(request.uri().getAuthority())) {
                return notFound();
            }
            Optional<String> normalized = AssetPaths.normalize(request.uri().getRawPath());
            if (normalized.isEmpty()) {
                return notFound(); // rejected path: deterministic 404, no echo of input
            }
            String path = normalized.get().isEmpty() ? "index.html" : normalized.get();
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
            AssetSource.Asset found = asset.get();
            return new AssetResponse(200, headers, found.size(), () -> openQuietly(found));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.ERROR, "Asset resolution failed", e);
            return serverError();
        }
    }

    private static InputStream openQuietly(AssetSource.Asset asset) {
        try {
            return asset.open().open();
        } catch (IOException e) {
            LOG.log(Level.ERROR, "Asset stream open failed", e);
            return InputStream.nullInputStream();
        }
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
}
