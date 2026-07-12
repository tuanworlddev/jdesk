package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.webview.spi.AssetRequest;
import dev.jdesk.webview.spi.AssetResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Production {@code jdesk://app/} resolver behavior (spec section 9.1): correct types
 * and cache headers, security headers on every response, deterministic 404/500 pages
 * that never echo the request or leak paths, strict scheme/authority/method checks.
 */
class AssetResolverTest {

    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "Content-Security-Policy", "default-src 'none'",
            "X-Content-Type-Options", "nosniff");

    private final MapAssetSource source = new MapAssetSource()
            .put("index.html", bytes("<html>home</html>"))
            .put("main.js", bytes("console.log('main')"))
            .put("app.3f9d2c1a.js", bytes("console.log('hashed')"))
            .put("styles.css", bytes("body{}"))
            .put("img/logo.png", bytes("PNG"));

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AssetResolver resolver(boolean spaFallback) {
        return new AssetResolver(source, spaFallback, SECURITY_HEADERS);
    }

    private static AssetRequest get(String uri) {
        return new AssetRequest(URI.create(uri), "GET");
    }

    private static AssetRequest post(String uri, byte[] body) {
        return new AssetRequest(URI.create(uri), "POST", body, Map.of());
    }

    private static String body(AssetResponse response) {
        try (InputStream in = response.body().get()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertSecurityHeaders(AssetResponse response) {
        assertThat(response.headers())
                .containsEntry("Content-Security-Policy", "default-src 'none'")
                .containsEntry("X-Content-Type-Options", "nosniff");
    }

    @Test
    void servesHtmlWithNoCacheAndSecurityHeaders() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/index.html"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers())
                .containsEntry("Content-Type", "text/html; charset=utf-8")
                .containsEntry("Cache-Control", "no-cache");
        assertSecurityHeaders(response);
        assertThat(body(response)).isEqualTo("<html>home</html>");
        assertThat(response.contentLength()).isEqualTo(bytes("<html>home</html>").length);
    }

    @Test
    void hashedAssetGetsImmutableCacheHeader() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/app.3f9d2c1a.js"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers())
                .containsEntry("Content-Type", "text/javascript; charset=utf-8")
                .containsEntry("Cache-Control", "public, max-age=31536000, immutable");
    }

    @Test
    void unhashedScriptGetsNoCache() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/main.js"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Cache-Control", "no-cache");
    }

    @Test
    void rootServesIndexHtml() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Content-Type", "text/html; charset=utf-8");
        assertThat(body(response)).isEqualTo("<html>home</html>");
    }

    @Test
    void missingAssetIs404WithDeterministicBodyAndNoPathEcho() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/very-secret-name.js"));
        assertThat(response.status()).isEqualTo(404);
        String body = body(response);
        assertThat(body).contains("404 Not Found");
        assertThat(body).doesNotContain("very-secret-name");
        assertThat(response.headers())
                .containsEntry("Content-Type", "text/html; charset=utf-8")
                .containsEntry("Cache-Control", "no-cache");
        assertSecurityHeaders(response);
        // Deterministic: two misses produce byte-identical bodies.
        assertThat(body(resolver(false).handle(get("jdesk://app/other-miss")))).isEqualTo(body);
    }

    @Test
    void traversalRequestsAre404() {
        AssetResolver resolver = resolver(false);
        assertThat(resolver.handle(get("jdesk://app/../secret")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("jdesk://app/%2e%2e/secret")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("jdesk://app/..%2fsecret")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("jdesk://app/a%5cb")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("jdesk://app/a%00b")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("jdesk://app/c:/x")).status()).isEqualTo(404);
    }

    @Test
    void traversalShapesUnrepresentableInUriAreRejectedByAssetPaths() {
        // Raw backslash and raw control characters cannot appear in a java.net.URI;
        // platform adapters would hand them to AssetPaths, which rejects them.
        assertThat(AssetPaths.normalize("/a\\b")).isEmpty();
        assertThat(AssetPaths.normalize("/a\u0001b")).isEmpty();
        assertThat(AssetPaths.normalize("/a b ")).isEmpty();
    }

    @Test
    void wrongSchemeOrAuthorityIs404() {
        AssetResolver resolver = resolver(false);
        assertThat(resolver.handle(get("jdesk://evil/x")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("https://app/index.html")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("http://app/x")).status()).isEqualTo(404);
        assertThat(resolver.handle(get("file:///etc/passwd")).status()).isEqualTo(404);
    }

    @Test
    void unsupportedMethodsAre405AndPostToStaticAssetsIs404() {
        AssetResolver resolver = resolver(false);
        // POST to a static asset (no route owns it) is a plain 404: the source is read-only.
        assertThat(resolver.handle(post("jdesk://app/index.html", new byte[0])).status())
                .isEqualTo(404);
        // Methods outside the asset ABI are 405, with an Allow header.
        for (String verb : new String[] {"PUT", "DELETE", "PATCH", "OPTIONS"}) {
            AssetResponse response =
                    resolver.handle(new AssetRequest(URI.create("jdesk://app/index.html"), verb));
            assertThat(response.status()).as("method %s", verb).isEqualTo(405);
            assertThat(response.headers()).containsEntry("Allow", "GET, HEAD, POST");
        }
    }

    @Test
    void headIsServedLikeGet() {
        AssetResponse response = resolver(false)
                .handle(new AssetRequest(URI.create("jdesk://app/index.html"), "HEAD"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Content-Type", "text/html; charset=utf-8");
        assertThat(response.contentLength()).isEqualTo(bytes("<html>home</html>").length);
    }

    @Test
    void spaFallbackServesIndexForExtensionLessMiss() {
        AssetResponse response = resolver(true).handle(get("jdesk://app/some/route"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("<html>home</html>");
        assertThat(response.headers()).containsEntry("Content-Type", "text/html; charset=utf-8");
    }

    @Test
    void spaFallbackDoesNotApplyToMissesWithExtension() {
        AssetResponse response = resolver(true).handle(get("jdesk://app/some/route.js"));
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void spaFallbackOffMeans404ForExtensionLessMiss() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/some/route"));
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void ioExceptionFromSourceIs500WithDeterministicBody() {
        AssetSource throwing = normalizedPath -> {
            throw new IOException("disk exploded at /internal/secret/location");
        };
        AssetResolver resolver = new AssetResolver(throwing, false, SECURITY_HEADERS);
        AssetResponse response = resolver.handle(get("jdesk://app/index.html"));
        assertThat(response.status()).isEqualTo(500);
        String body = body(response);
        assertThat(body).contains("500 Internal Error");
        assertThat(body).doesNotContain("secret");
        assertThat(body).doesNotContain("exploded");
        assertSecurityHeaders(response);
        assertThat(response.headers()).containsEntry("Cache-Control", "no-cache");
    }

    @Test
    void nestedAssetGetsCorrectContentType() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/img/logo.png"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsEntry("Content-Type", "image/png");
    }

    @Test
    void schemeAndAuthorityChecksAreCaseInsensitive() {
        AssetResponse response = resolver(false).handle(get("JDESK://APP/index.html"));
        assertThat(response.status()).isEqualTo(200);
    }

    private static AssetRequest getRange(String uri, String range) {
        return new AssetRequest(URI.create(uri), "GET", Map.of("Range", range));
    }

    @Test
    void successResponsesAdvertiseAcceptRanges() {
        AssetResponse response = resolver(false).handle(get("jdesk://app/index.html"));
        assertThat(response.headers()).containsEntry("Accept-Ranges", "bytes");
    }

    @Test
    void rangeRequestGets206WithContentRangeAndSlicedBody() {
        // "<html>home</html>" is 17 bytes; ask for bytes 6-9 = "home".
        AssetResponse response = resolver(false)
                .handle(getRange("jdesk://app/index.html", "bytes=6-9"));
        assertThat(response.status()).isEqualTo(206);
        assertThat(response.headers()).containsEntry("Content-Range", "bytes 6-9/17");
        assertThat(response.contentLength()).isEqualTo(4);
        assertThat(body(response)).isEqualTo("home");
        assertSecurityHeaders(response);
    }

    @Test
    void openEndedRangeServesToEndOfAsset() {
        AssetResponse response = resolver(false)
                .handle(getRange("jdesk://app/index.html", "bytes=6-"));
        assertThat(response.status()).isEqualTo(206);
        assertThat(response.headers()).containsEntry("Content-Range", "bytes 6-16/17");
        assertThat(body(response)).isEqualTo("home</html>");
    }

    @Test
    void suffixRangeServesLastBytes() {
        AssetResponse response = resolver(false)
                .handle(getRange("jdesk://app/index.html", "bytes=-7"));
        assertThat(response.status()).isEqualTo(206);
        assertThat(response.headers()).containsEntry("Content-Range", "bytes 10-16/17");
        assertThat(body(response)).isEqualTo("</html>");
    }

    @Test
    void rangeEndIsClampedToAssetSize() {
        AssetResponse response = resolver(false)
                .handle(getRange("jdesk://app/index.html", "bytes=6-99999"));
        assertThat(response.status()).isEqualTo(206);
        assertThat(response.headers()).containsEntry("Content-Range", "bytes 6-16/17");
    }

    @Test
    void rangeBeyondAssetIs416WithTotalSize() {
        AssetResponse response = resolver(false)
                .handle(getRange("jdesk://app/index.html", "bytes=17-20"));
        assertThat(response.status()).isEqualTo(416);
        assertThat(response.headers()).containsEntry("Content-Range", "bytes */17");
        assertThat(response.contentLength()).isZero();
        assertSecurityHeaders(response);
    }

    @Test
    void malformedOrMultiRangeHeadersServeFull200() {
        AssetResolver resolver = resolver(false);
        for (String bad : new String[] {"bytes=abc", "chunks=0-5", "bytes=5-2", "bytes=0-2,4-6"}) {
            AssetResponse response = resolver.handle(getRange("jdesk://app/index.html", bad));
            assertThat(response.status()).as("Range: %s", bad).isEqualTo(200);
            assertThat(body(response)).isEqualTo("<html>home</html>");
        }
    }

    @Test
    void rangeOn404MissStays404() {
        AssetResponse response = resolver(false)
                .handle(getRange("jdesk://app/missing.mp4", "bytes=0-100"));
        assertThat(response.status()).isEqualTo(404);
    }

    // ---- app-defined asset routes ----

    private AssetResolver resolverWithRoute(String prefix, dev.jdesk.api.AssetRoute route) {
        return new AssetResolver(source, false, SECURITY_HEADERS, Map.of(prefix, route));
    }

    @Test
    void assetRouteServesUnderItsPrefix() {
        AssetResolver resolver = resolverWithRoute("proxy/images", request ->
                Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                        ("payload:" + request.path()).getBytes(StandardCharsets.UTF_8),
                        "image/jpeg")));
        AssetResponse response = resolver.handle(get("jdesk://app/proxy/images/p1.jpg"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("payload:p1.jpg");
        assertThat(response.headers())
                .containsEntry("Content-Type", "image/jpeg")
                .containsEntry("Accept-Ranges", "bytes");
        assertSecurityHeaders(response);
    }

    @Test
    void assetRouteAnswersRangeRequestsWith206() {
        AssetResolver resolver = resolverWithRoute("proxy", request ->
                Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                        "0123456789".getBytes(StandardCharsets.UTF_8), "application/octet-stream")));
        AssetResponse part = resolver.handle(getRange("jdesk://app/proxy/blob", "bytes=2-5"));
        assertThat(part.status()).isEqualTo(206);
        assertThat(part.headers()).containsEntry("Content-Range", "bytes 2-5/10");
        assertThat(body(part)).isEqualTo("2345");
    }

    @Test
    void assetRouteCanSetCacheHeaders() {
        AssetResolver resolver = resolverWithRoute("proxy", request ->
                Optional.of(new dev.jdesk.api.AssetRoute.Response("image/png", 1,
                        java.io.InputStream::nullInputStream,
                        Map.of("Cache-Control", "public, max-age=3600"))));
        AssetResponse response = resolver.handle(get("jdesk://app/proxy/x"));
        assertThat(response.headers()).containsEntry("Cache-Control", "public, max-age=3600");
    }

    @Test
    void assetRouteEmptyIs404AndIoExceptionIs500() {
        AssetResolver missing = resolverWithRoute("proxy", request -> Optional.empty());
        assertThat(missing.handle(get("jdesk://app/proxy/nope")).status()).isEqualTo(404);

        AssetResolver failing = resolverWithRoute("proxy", request -> {
            throw new IOException("upstream at /secret/path exploded");
        });
        AssetResponse error = failing.handle(get("jdesk://app/proxy/x"));
        assertThat(error.status()).isEqualTo(500);
        assertThat(body(error)).doesNotContain("secret");
    }

    @Test
    void assetRouteTakesPrecedenceOverAssetSourceUnderItsPrefix() {
        // Source has img/logo.png; a route on "img" owns the whole prefix.
        AssetResolver resolver = resolverWithRoute("img", request ->
                Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                        "routed".getBytes(StandardCharsets.UTF_8), "text/plain")));
        assertThat(body(resolver.handle(get("jdesk://app/img/logo.png")))).isEqualTo("routed");
        // Outside the prefix the source still serves.
        assertThat(resolver.handle(get("jdesk://app/index.html")).status()).isEqualTo(200);
    }

    @Test
    void assetRoutePathIsNormalizedBeforeMatching() {
        AssetResolver resolver = resolverWithRoute("proxy", request ->
                Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                        request.path().getBytes(StandardCharsets.UTF_8), "text/plain")));
        // Traversal shapes are rejected before any route sees them.
        assertThat(resolver.handle(get("jdesk://app/proxy/%2e%2e/secret")).status())
                .isEqualTo(404);
    }

    @Test
    void emptyPathUriServesIndex() {
        // Some engines report "jdesk://app" with an empty path for the root document.
        Optional<String> normalized = AssetPaths.normalize("");
        assertThat(normalized).contains("");
        AssetResponse response = resolver(false).handle(get("jdesk://app"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("<html>home</html>");
    }

    // ---- binary upload channel (POST body -> app route, GAP-002) ----

    private AssetResolver resolverWithRoute(
            String prefix, dev.jdesk.api.AssetRoute route, long maxUploadBytes) {
        return new AssetResolver(
                source, false, SECURITY_HEADERS, Map.of(prefix, route), maxUploadBytes);
    }

    @Test
    void postToRouteDeliversMethodAndBodyAsRawBytes() {
        java.util.concurrent.atomic.AtomicReference<dev.jdesk.api.AssetRoute.Request> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        AssetResolver resolver = resolverWithRoute("upload", request -> {
            seen.set(request);
            return Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                    ("len:" + request.body().length).getBytes(StandardCharsets.UTF_8), "text/plain"));
        });
        byte[] payload = {0, 1, 2, 3, (byte) 0xFF, (byte) 0x80, 127, -1};
        AssetResponse response = resolver.handle(post("jdesk://app/upload/blob.bin", payload));
        assertThat(response.status()).isEqualTo(200);
        assertThat(seen.get().method()).isEqualTo("POST");
        assertThat(seen.get().path()).isEqualTo("blob.bin");
        assertThat(seen.get().body()).isEqualTo(payload); // exact bytes, no base64, no mangling
        assertThat(body(response)).isEqualTo("len:8");
    }

    @Test
    void getToRouteStillExposesGetMethodAndEmptyBody() {
        java.util.concurrent.atomic.AtomicReference<dev.jdesk.api.AssetRoute.Request> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        AssetResolver resolver = resolverWithRoute("proxy", request -> {
            seen.set(request);
            return Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                    "ok".getBytes(StandardCharsets.UTF_8), "text/plain"));
        });
        assertThat(resolver.handle(get("jdesk://app/proxy/x")).status()).isEqualTo(200);
        assertThat(seen.get().method()).isEqualTo("GET");
        assertThat(seen.get().body()).isEmpty();
    }

    @Test
    void routeEmptyResponseIs200WithNoBody() {
        AssetResolver resolver = resolverWithRoute("upload",
                request -> Optional.of(dev.jdesk.api.AssetRoute.Response.empty()));
        AssetResponse response = resolver.handle(post("jdesk://app/upload/x", new byte[] {42}));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentLength()).isZero();
        assertThat(body(response)).isEmpty();
    }

    @Test
    void postBodyOverCapIs413AndRouteIsNeverInvoked() {
        java.util.concurrent.atomic.AtomicBoolean invoked =
                new java.util.concurrent.atomic.AtomicBoolean();
        AssetResolver resolver = resolverWithRoute("upload", request -> {
            invoked.set(true);
            return Optional.of(dev.jdesk.api.AssetRoute.Response.empty());
        }, 8); // 8-byte cap
        // Exactly at the cap: accepted.
        assertThat(resolver.handle(post("jdesk://app/upload/x", new byte[8])).status()).isEqualTo(200);
        assertThat(invoked.get()).isTrue();
        // One byte over: rejected with 413 before the route runs.
        invoked.set(false);
        AssetResponse tooBig = resolver.handle(post("jdesk://app/upload/x", new byte[9]));
        assertThat(tooBig.status()).isEqualTo(413);
        assertThat(invoked.get())
                .as("oversize upload must be rejected before the app route is invoked")
                .isFalse();
    }
}
