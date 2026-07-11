package dev.jdesk.runtime.boot;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.assets.AssetResolver;
import dev.jdesk.runtime.capability.CapabilityEngine;
import dev.jdesk.runtime.capability.OriginNormalizer;
import dev.jdesk.runtime.ipc.CommandDispatcher;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import dev.jdesk.runtime.json.JsonCodec;
import dev.jdesk.runtime.lifecycle.LifecycleStateMachine;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;
import dev.jdesk.webview.spi.PlatformWindow;
import dev.jdesk.webview.spi.WebViewSnapshot;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires one application: lifecycle, capability engine, asset resolver, and one
 * dispatcher per window over the platform SPI. Platform-agnostic by construction —
 * the same wiring runs against every adapter and against test fakes in unit tests.
 */
public final class JDeskRuntime implements AutoCloseable {
    private static final Logger LOG = System.getLogger(JDeskRuntime.class.getName());
    public static final String APP_ORIGIN = "jdesk://app";

    private final ApplicationSpec spec;
    private final PlatformProvider provider;
    private final RuntimeOptions options;
    private final JsonCodec codec = new JacksonJsonCodec();
    private final LifecycleStateMachine lifecycle;
    private final CapabilityEngine capabilityEngine;
    private final NavigationPolicy navigationPolicy;
    private final Map<WindowId, WindowRuntime> windows = new ConcurrentHashMap<>();
    private final AtomicInteger openWindows = new AtomicInteger();
    private PlatformApplication platformApp;

    /** Per-window wiring: dispatcher plus the tracked current top-level origin. */
    private final class WindowRuntime {
        final PlatformWindow window;
        final CommandDispatcher dispatcher;
        volatile String currentOrigin;

        WindowRuntime(PlatformWindow window, CommandDispatcher dispatcher, String initialOrigin) {
            this.window = window;
            this.dispatcher = dispatcher;
            this.currentOrigin = initialOrigin;
        }
    }

    public JDeskRuntime(ApplicationSpec spec, PlatformProvider provider, RuntimeOptions options) {
        this.spec = spec;
        this.provider = provider;
        this.options = options;
        this.lifecycle = new LifecycleStateMachine(spec.lifecycleListeners());

        Set<String> allowedOrigins = new LinkedHashSet<>();
        allowedOrigins.add(APP_ORIGIN);
        if (options.devMode()) {
            spec.devServerUrl().ifPresent(url ->
                    allowedOrigins.add(OriginNormalizer.normalize(url)));
        }
        this.capabilityEngine = new CapabilityEngine(spec.capabilities(), allowedOrigins);
        this.navigationPolicy = new NavigationPolicy(allowedOrigins);
    }

    /** Runs the application to completion; returns the process exit code. */
    public int run() {
        lifecycle.starting();
        AssetResolver assetResolver = new AssetResolver(
                options.assetSource(), options.spaFallback(), options.securityHeaders());
        Optional<String> devOrigin = options.devMode()
                ? spec.devServerUrl().map(OriginNormalizer::normalize)
                : Optional.empty();
        platformApp = provider.createApplication(new PlatformApplicationConfig(
                spec.id(), options.devMode(), devOrigin, assetResolver));
        try {
            for (WindowConfig config : spec.windows()) {
                createWindowOnUiThread(config);
            }
            lifecycle.ready();
            platformApp.runEventLoop();
        } finally {
            shutdown();
        }
        return 0;
    }

    private void createWindowOnUiThread(WindowConfig config) {
        PlatformWindow window = platformApp.createWindow(new NativeWindowConfig(
                config.id(), config.title(), config.width(), config.height(),
                config.resizable(), config.entry(), options.devMode()));

        CommandDispatcher dispatcher = new CommandDispatcher(
                config.id(), spec.commands(), capabilityEngine, codec, options.limits(),
                provider.info(), options.overflowPolicy(), options.navigationGrace(),
                json -> platformApp.ui().execute(() -> window.webView().postJson(json)));

        String initialOrigin = originOfEntry(config);
        WindowRuntime windowRuntime = new WindowRuntime(window, dispatcher, initialOrigin);
        windows.put(config.id(), windowRuntime);
        openWindows.incrementAndGet();

        window.webView().onMessage(raw ->
                dispatcher.onMessage(raw, windowRuntime.currentOrigin));
        window.webView().onNavigation(request -> navigationPolicy.decide(request));
        window.webView().onNavigationCommitted(uri -> {
            dispatcher.onNavigationCommitted();
            try {
                windowRuntime.currentOrigin = originOf(uri.toString());
            } catch (RuntimeException e) {
                // Origin-less documents (about:blank) keep the previous origin; the
                // capability engine still gates on it, so nothing widens.
                LOG.log(Level.DEBUG, "No origin for committed navigation: {0}", uri);
            }
            // The fresh document's init script is installed; hand it its session nonce.
            window.webView().postJson(dispatcher.currentNonceEnvelope());
        });
        window.onCloseRequested(() -> lifecycle.closeRequested(config.id()));
        window.onClosed(() -> windowClosed(config.id()));
        window.webView().navigate(config.entry());
        window.show();
    }

    private static String originOfEntry(WindowConfig config) {
        return originOf(config.entry().toString());
    }

    private static String originOf(String uri) {
        java.net.URI parsed = java.net.URI.create(uri);
        if (parsed.getScheme() == null || parsed.getAuthority() == null) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Window entry must have an origin: " + uri);
        }
        return parsed.getScheme() + "://" + parsed.getAuthority();
    }

    /**
     * Opens an additional window after startup. Safe from any thread; the native
     * creation is marshalled onto the UI thread.
     */
    public CompletionStage<WindowId> openWindow(WindowConfig config) {
        if (windows.containsKey(config.id())) {
            return CompletableFuture.failedFuture(new JDeskException(
                    ErrorCode.ILLEGAL_STATE, "Window id already in use: " + config.id()));
        }
        return platformApp.ui().submit(() -> {
            createWindowOnUiThread(config);
            return config.id();
        });
    }

    /** Closes one window from any thread; the native close runs on the UI thread. */
    public CompletionStage<Void> closeWindow(WindowId windowId) {
        WindowRuntime windowRuntime = windows.get(windowId);
        if (windowRuntime == null) {
            return CompletableFuture.failedFuture(
                    new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window"));
        }
        return platformApp.ui().submit(() -> {
            windowRuntime.window.close();
            windowClosed(windowId);
            return null;
        });
    }

    /** Captures the window's WebView through the engine's real snapshot API. */
    public CompletionStage<WebViewSnapshot> snapshot(WindowId windowId) {
        WindowRuntime windowRuntime = windows.get(windowId);
        if (windowRuntime == null) {
            return CompletableFuture.failedFuture(
                    new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window"));
        }
        return platformApp.ui().submit(() -> windowRuntime.window.webView().snapshot())
                .thenCompose(stage -> stage);
    }

    /** UI dispatcher of the running platform application; null before {@link #run()}. */
    public UiDispatcher ui() {
        return platformApp == null ? null : platformApp.ui();
    }

    /** Number of currently open windows (for leak checks). */
    public int openWindowCount() {
        return windows.size();
    }

    /** Emitter for events targeted at one window. */
    public EventEmitter emitter(WindowId windowId) {
        WindowRuntime windowRuntime = windows.get(windowId);
        if (windowRuntime == null) {
            throw new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window");
        }
        return windowRuntime.dispatcher.emitter();
    }

    /** Close-request veto hook for platform adapters. */
    public boolean closeRequested(WindowId windowId) {
        return lifecycle.closeRequested(windowId);
    }

    /** Called by the platform layer when a window has closed. */
    public void windowClosed(WindowId windowId) {
        WindowRuntime windowRuntime = windows.remove(windowId);
        if (windowRuntime != null) {
            windowRuntime.dispatcher.close();
            if (openWindows.decrementAndGet() <= 0) {
                platformApp.requestStop();
            }
        }
    }

    public LifecycleStateMachine lifecycle() {
        return lifecycle;
    }

    public int pendingInvocations() {
        return windows.values().stream().mapToInt(w -> w.dispatcher.pendingInvocations()).sum();
    }

    private void shutdown() {
        lifecycle.stopping();
        Map<WindowId, WindowRuntime> remaining = new LinkedHashMap<>(windows);
        windows.clear();
        for (WindowRuntime windowRuntime : remaining.values()) {
            try {
                windowRuntime.dispatcher.close();
                windowRuntime.window.close();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Window shutdown failed", e);
            }
        }
        try {
            platformApp.close();
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Platform application shutdown failed", e);
        }
        lifecycle.stopped();
    }

    @Override
    public void close() {
        if (platformApp != null) {
            platformApp.requestStop();
        }
    }
}
