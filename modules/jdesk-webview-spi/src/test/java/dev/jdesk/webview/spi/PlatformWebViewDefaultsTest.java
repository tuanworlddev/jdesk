package dev.jdesk.webview.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.Subscription;
import dev.jdesk.api.WebViewCookie;
import dev.jdesk.api.WebViewCookieKey;
import dev.jdesk.api.WebViewDataType;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PlatformWebViewDefaultsTest {
    private static final PlatformWebView BARE = new PlatformWebView() {
        @Override public void navigate(URI uri) { }
        @Override public void postJson(String json) { }
        @Override public CompletionStage<String> evaluate(String script) {
            return CompletableFuture.completedFuture("");
        }
        @Override public Subscription onMessage(Consumer<String> listener) {
            return () -> { };
        }
        @Override public Subscription onNavigation(NavigationListener listener) {
            return () -> { };
        }
        @Override public Subscription onNavigationCommitted(Consumer<URI> listener) {
            return () -> { };
        }
        @Override public CompletionStage<WebViewSnapshot> snapshot() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public WebViewDiagnostics diagnostics() {
            return new WebViewDiagnostics(Optional.empty(), Optional.empty(), Optional.empty());
        }
        @Override public boolean devToolsEnabled() {
            return false;
        }
        @Override public void close() { }
    };

    @Test
    void unsupportedSessionDataOperationsFailLoudly() {
        WebViewCookie cookie = WebViewCookie.session(
                "sid", "value", "example.com", "/", false, true);

        for (CompletionStage<?> operation : new CompletionStage<?>[] {
                BARE.clearData(Set.of(WebViewDataType.COOKIES)),
                BARE.cookies(),
                BARE.setCookie(cookie),
                BARE.deleteCookie(new WebViewCookieKey("sid", "example.com", "/"))
        }) {
            assertThatThrownBy(() -> operation.toCompletableFuture().join())
                    .hasCauseInstanceOf(JDeskException.class);
            assertThat(operation.toCompletableFuture().handle((ignored, error) -> {
                Throwable actual = error instanceof java.util.concurrent.CompletionException
                        ? error.getCause() : error;
                return ((JDeskException) actual).code();
            }).join())
                    .isEqualTo(ErrorCode.ILLEGAL_STATE);
        }
    }
}
