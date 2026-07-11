package dev.jdesk.webview.spi;

import dev.jdesk.api.Subscription;
import java.net.URI;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One system WebView. Mutating calls are UI-thread only; the runtime marshals through
 * {@link dev.jdesk.api.UiDispatcher}. Strings crossing this boundary are JSON envelopes;
 * the WebView never receives Java objects.
 */
public interface PlatformWebView extends AutoCloseable {
    void navigate(URI uri);

    /** Posts one JSON envelope to the page's bridge listener. */
    void postJson(String json);

    /** Evaluates script in the page; development/diagnostics use only. */
    CompletionStage<String> evaluate(String script);

    /** JSON envelopes arriving from the page. Listener runs on the UI thread; copy and dispatch. */
    Subscription onMessage(Consumer<String> listener);

    Subscription onNavigation(NavigationListener listener);

    /**
     * Fires when a new main-frame document is created (WebView2 ContentLoading,
     * WKWebView didCommitNavigation, WebKitGTK load-committed): the bridge init script
     * exists, page scripts have not run. The runtime delivers the per-navigation nonce
     * here. UI thread.
     */
    Subscription onNavigationCommitted(java.util.function.Consumer<java.net.URI> listener);

    /** Engine renderer/process failure notification. Implementations must never invoke
     * listeners after close. The default preserves compatibility for embedders that
     * cannot expose a process model. */
    default Subscription onProcessFailure(Consumer<WebViewProcessFailure> listener) {
        return () -> { };
    }

    /** Real engine capture API; never a synthetic image. */
    CompletionStage<WebViewSnapshot> snapshot();

    WebViewDiagnostics diagnostics();

    /** Actual engine setting after native WebView initialization. */
    boolean devToolsEnabled();

    @Override
    void close();
}
