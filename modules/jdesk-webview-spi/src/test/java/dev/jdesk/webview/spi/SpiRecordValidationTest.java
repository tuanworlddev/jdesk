package dev.jdesk.webview.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.jdesk.api.WindowId;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpiRecordValidationTest {

    @Test
    void assetResponseAllowsOnlyKnownStatuses() {
        Map<String, String> headers = Map.of("Content-Type", "text/plain");
        for (int status : new int[] {200, 206, 404, 416, 500}) {
            assertThat(new AssetResponse(status, headers, 0, InputStream::nullInputStream).status())
                    .isEqualTo(status);
        }
        for (int status : new int[] {0, 100, 201, 301, 403, 503}) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new AssetResponse(status, headers, 0, InputStream::nullInputStream));
        }
    }

    @Test
    void assetResponseCopiesHeadersDefensively() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("Content-Type", "text/plain");
        AssetResponse response = new AssetResponse(200, mutable, -1, InputStream::nullInputStream);
        mutable.put("X-Injected", "yes");
        assertThat(response.headers()).doesNotContainKey("X-Injected");
    }

    @Test
    void assetRequestRequiresUriAndMethod() {
        assertThatNullPointerException().isThrownBy(() -> new AssetRequest(null, "GET"));
        assertThatNullPointerException().isThrownBy(
                () -> new AssetRequest(URI.create("jdesk://app/index.html"), null));
        assertThatNullPointerException().isThrownBy(
                () -> new AssetRequest(URI.create("jdesk://app/index.html"), "GET", null));
    }

    @Test
    void assetRequestHeaderLookupIsCaseInsensitive() {
        AssetRequest request = new AssetRequest(URI.create("jdesk://app/video.mp4"), "GET",
                Map.of("Range", "bytes=0-1023"));
        assertThat(request.header("range")).contains("bytes=0-1023");
        assertThat(request.header("RANGE")).contains("bytes=0-1023");
        assertThat(request.header("accept")).isEmpty();
        // Two-arg convenience constructor means no headers.
        assertThat(new AssetRequest(URI.create("jdesk://app/x"), "GET").headers()).isEmpty();
    }

    @Test
    void snapshotValidatesDimensionsAndCopiesPixels() {
        byte[] png = new byte[] {1, 2, 3};
        WebViewSnapshot snapshot = new WebViewSnapshot(10, 20, png);
        png[0] = 99;
        assertThat(snapshot.png()[0]).isEqualTo((byte) 1);
        snapshot.png()[1] = 42;
        assertThat(snapshot.png()[1]).isEqualTo((byte) 2);
        assertThatIllegalArgumentException().isThrownBy(() -> new WebViewSnapshot(0, 5, png));
        assertThatIllegalArgumentException().isThrownBy(() -> new WebViewSnapshot(5, -1, png));
    }

    @Test
    void platformApplicationConfigForbidsDevOriginInProduction() {
        AssetHandler handler = request -> new AssetResponse(404, Map.of(), 0, InputStream::nullInputStream);
        assertThatIllegalArgumentException().isThrownBy(() -> new PlatformApplicationConfig(
                "dev.example.app", false, Optional.of("http://127.0.0.1:5173"), handler));
        PlatformApplicationConfig dev = new PlatformApplicationConfig(
                "dev.example.app", true, Optional.of("http://127.0.0.1:5173"), handler);
        assertThat(dev.devServerOrigin()).contains("http://127.0.0.1:5173");
        PlatformApplicationConfig prod = new PlatformApplicationConfig(
                "dev.example.app", false, Optional.empty(), handler);
        assertThat(prod.devMode()).isFalse();
    }

    @Test
    void nativeWindowConfigRequiresIdTitleEntry() {
        WindowId id = new WindowId("main");
        URI entry = URI.create("jdesk://app/index.html");
        assertThatNullPointerException().isThrownBy(
                () -> new NativeWindowConfig(null, "t", 1, 1, true, entry, false));
        assertThatNullPointerException().isThrownBy(
                () -> new NativeWindowConfig(id, null, 1, 1, true, entry, false));
        assertThatNullPointerException().isThrownBy(
                () -> new NativeWindowConfig(id, "t", 1, 1, true, null, false));
        assertThat(new NativeWindowConfig(id, "t", 800, 600, true, entry, false).width())
                .isEqualTo(800);
    }

    @Test
    void navigationRequestRequiresUri() {
        assertThatNullPointerException().isThrownBy(
                () -> new NavigationRequest(null, true, false));
        NavigationRequest request =
                new NavigationRequest(URI.create("https://example.com"), true, true);
        assertThat(request.mainFrame()).isTrue();
        assertThat(request.userInitiated()).isTrue();
    }

    @Test
    void processFailureRequiresKindAndDetail() {
        assertThatNullPointerException().isThrownBy(
                () -> new WebViewProcessFailure(null, "x"));
        assertThatNullPointerException().isThrownBy(
                () -> new WebViewProcessFailure(WebViewProcessFailure.Kind.UNKNOWN, null));
        assertThat(new WebViewProcessFailure(
                WebViewProcessFailure.Kind.RENDER_PROCESS_EXITED, "exit 5").kind())
                .isEqualTo(WebViewProcessFailure.Kind.RENDER_PROCESS_EXITED);
    }

    @Test
    void diagnosticsHoldsOptionals() {
        WebViewDiagnostics diagnostics = new WebViewDiagnostics(
                Optional.of("WebKit 620"), Optional.empty(), Optional.of(123L));
        assertThat(diagnostics.engineVersion()).contains("WebKit 620");
        assertThat(diagnostics.userAgent()).isEmpty();
        assertThat(diagnostics.webViewProcessId()).contains(123L);
    }
}
