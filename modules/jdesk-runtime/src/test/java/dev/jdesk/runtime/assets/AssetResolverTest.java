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
    void nonGetOrHeadMethodsAre404() {
        AssetResolver resolver = resolver(false);
        assertThat(resolver.handle(new AssetRequest(URI.create("jdesk://app/index.html"), "POST")).status())
                .isEqualTo(404);
        assertThat(resolver.handle(new AssetRequest(URI.create("jdesk://app/index.html"), "PUT")).status())
                .isEqualTo(404);
        assertThat(resolver.handle(new AssetRequest(URI.create("jdesk://app/index.html"), "DELETE")).status())
                .isEqualTo(404);
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

    @Test
    void emptyPathUriServesIndex() {
        // Some engines report "jdesk://app" with an empty path for the root document.
        Optional<String> normalized = AssetPaths.normalize("");
        assertThat(normalized).contains("");
        AssetResponse response = resolver(false).handle(get("jdesk://app"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("<html>home</html>");
    }
}
