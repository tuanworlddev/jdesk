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

    /** Real engine capture API; never a synthetic image. */
    CompletionStage<WebViewSnapshot> snapshot();

    WebViewDiagnostics diagnostics();

    @Override
    void close();
}
