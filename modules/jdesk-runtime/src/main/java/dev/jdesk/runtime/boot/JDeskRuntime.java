package dev.jdesk.runtime.boot;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.api.WindowHandle;
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
import dev.jdesk.webview.spi.WebViewDiagnostics;
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
import java.util.function.Consumer;

/**
 * Wires one application: lifecycle, capability engine, asset resolver, and one
 * dispatcher per window over the platform SPI. Platform-agnostic by construction —
 * the same wiring runs against every adapter and against test fakes in unit tests.
 */
public final class JDeskRuntime implements ApplicationHandle, AutoCloseable {
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
    private final Set<WindowId> windowIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger openWindows = new AtomicInteger();
    private PlatformApplication platformApp;

    /** Per-window wiring: dispatcher plus the tracked current top-level origin. */
    private final class WindowRuntime implements WindowHandle {
        final PlatformWindow window;
        final CommandDispatcher dispatcher;
        volatile String currentOrigin;

        WindowRuntime(PlatformWindow window, CommandDispatcher dispatcher, String initialOrigin) {
            this.window = window;
            this.dispatcher = dispatcher;
            this.currentOrigin = initialOrigin;
        }

        @Override
        public WindowId id() {
            return window.id();
        }

        @Override
        public EventEmitter events() {
            return dispatcher.emitter();
        }

        @Override public CompletionStage<Void> show() { return controlWindow(id(), PlatformWindow::show); }
        @Override public CompletionStage<Void> hide() { return controlWindow(id(), PlatformWindow::hide); }
        @Override public CompletionStage<Void> focus() { return controlWindow(id(), PlatformWindow::focus); }
        @Override public CompletionStage<Void> setTitle(String title) {
            java.util.Objects.requireNonNull(title, "title");
            return controlWindow(id(), w -> w.setTitle(title));
        }
        @Override public CompletionStage<Void> setBounds(int x, int y, int width, int height) {
            return controlWindow(id(), w -> w.setBounds(
                    new dev.jdesk.webview.spi.WindowBounds(x, y, width, height)));
        }
        @Override public CompletionStage<Void> setMinimized(boolean value) {
            return controlWindow(id(), w -> w.setMinimized(value));
        }
        @Override public CompletionStage<Void> setMaximized(boolean value) {
            return controlWindow(id(), w -> w.setMaximized(value));
        }
        @Override public CompletionStage<Void> setFullscreen(boolean value) {
            return controlWindow(id(), w -> w.setFullscreen(value));
        }
        @Override public CompletionStage<Void> setAlwaysOnTop(boolean value) {
            return controlWindow(id(), w -> w.setAlwaysOnTop(value));
        }

        @Override
        public CompletionStage<Void> close() {
            return closeWindow(id());
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
                windowIds.add(config.id());
                createWindowOnUiThread(config);
            }
            lifecycle.ready(this);
            platformApp.runEventLoop();
        } finally {
            shutdown();
        }
        return 0;
    }

    private void createWindowOnUiThread(WindowConfig config) {
        WindowConfig launchConfig = devWindowConfig(config);
        PlatformWindow window = platformApp.createWindow(new NativeWindowConfig(
                launchConfig.id(), launchConfig.title(), launchConfig.width(), launchConfig.height(),
                launchConfig.resizable(), launchConfig.entry(), options.devMode()));
        try {
            CommandDispatcher dispatcher = new CommandDispatcher(
                    launchConfig.id(), spec.commands(), spec.frontendEvents(), capabilityEngine,
                    codec, options.limits(),
                    provider.info(), options.overflowPolicy(), options.navigationGrace(),
                    (java.util.function.Function<String, CompletionStage<Void>>) json ->
                            platformApp.ui().submit(() -> {
                        window.webView().postJson(json);
                        return null;
                    }), this);

            String initialOrigin = originOfEntry(launchConfig);
            WindowRuntime windowRuntime = new WindowRuntime(window, dispatcher, initialOrigin);
            windows.put(launchConfig.id(), windowRuntime);
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
            window.webView().onProcessFailure(failure -> {
                LOG.log(Level.WARNING, "WebView process failure in window {0}: {1}",
                        launchConfig.id(), failure);
                // Reject/cancel work owned by the dead document immediately. A second
                // rotation occurs on commit, ensuring the replacement document can
                // never reuse a nonce created during recovery.
                dispatcher.onNavigationCommitted();
                try {
                    window.webView().navigate(launchConfig.entry());
                } catch (RuntimeException recoveryFailure) {
                    LOG.log(Level.ERROR, "WebView recovery failed for " + launchConfig.id(),
                            recoveryFailure);
                    window.close();
                }
            });
            window.onCloseRequested(() -> lifecycle.closeRequested(launchConfig.id()));
            window.onClosed(() -> windowClosed(launchConfig.id()));
            window.webView().navigate(launchConfig.entry());
            window.show();
        } catch (RuntimeException e) {
            windowIds.remove(config.id());
            try {
                window.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private WindowConfig devWindowConfig(WindowConfig config) {
        if (!options.devMode() || spec.devServerUrl().isEmpty()) {
            return config;
        }
        return new WindowConfig(config.id(), config.title(), config.width(), config.height(),
                config.resizable(), java.net.URI.create(spec.devServerUrl().get()));
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
    @Override
    public CompletionStage<WindowHandle> openWindow(WindowConfig config) {
        if (!windowIds.add(config.id())) {
            return CompletableFuture.failedFuture(new JDeskException(
                    ErrorCode.ILLEGAL_STATE, "Window id already in use: " + config.id()));
        }
        CompletionStage<WindowHandle> opened = platformApp.ui().submit(() -> {
            createWindowOnUiThread(config);
            return windows.get(config.id());
        });
        return opened.whenComplete((ignored, failure) -> {
            if (failure != null) {
                windowIds.remove(config.id());
            }
        });
    }

    @Override
    public Optional<WindowHandle> window(WindowId windowId) {
        return Optional.ofNullable(windows.get(windowId));
    }

    @Override
    public PlatformInfo platform() {
        return provider.info();
    }

    @Override public CompletionStage<Void> openExternal(java.net.URI uri) {
        java.util.Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            return CompletableFuture.failedFuture(new JDeskException(ErrorCode.INVALID_REQUEST,
                    "External URI must be HTTP(S), have a host, and contain no credentials"));
        }
        return platformApp.ui().submit(() -> { platformApp.openExternal(uri); return null; });
    }

    @Override public CompletionStage<String> readClipboardText() {
        return platformApp.ui().submit(platformApp::readClipboardText);
    }
    @Override public CompletionStage<Void> writeClipboardText(String text) {
        java.util.Objects.requireNonNull(text, "text");
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
            return CompletableFuture.failedFuture(new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Clipboard text exceeds 1 MiB"));
        return platformApp.ui().submit(() -> { platformApp.writeClipboardText(text); return null; });
    }
    @Override public CompletionStage<dev.jdesk.api.MessageDialogResult> showMessageDialog(
            dev.jdesk.api.MessageDialog dialog) {
        java.util.Objects.requireNonNull(dialog, "dialog");
        if (dialog.title().length() > 512 || dialog.message().length() > 16_384
                || dialog.buttons().stream().anyMatch(label -> label.length() > 128)) {
            return CompletableFuture.failedFuture(new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Message dialog text exceeds platform-safe limits"));
        }
        return platformApp.ui().submit(() -> platformApp.showMessageDialog(dialog));
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

    private CompletionStage<Void> controlWindow(WindowId windowId, Consumer<PlatformWindow> action) {
        WindowRuntime runtime = windows.get(windowId);
        if (runtime == null) return CompletableFuture.failedFuture(
                new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window"));
        return platformApp.ui().submit(() -> { action.accept(runtime.window); return null; });
    }

    /** Evaluates script in the page; development/diagnostics use only. */
    public CompletionStage<String> evaluate(WindowId windowId, String script) {
        WindowRuntime windowRuntime = windows.get(windowId);
        if (windowRuntime == null) {
            return CompletableFuture.failedFuture(
                    new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window"));
        }
        return platformApp.ui().submit(() -> windowRuntime.window.webView().evaluate(script))
                .thenCompose(stage -> stage);
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

    /** Returns native engine diagnostics for evidence and support tooling. */
    public CompletionStage<WebViewDiagnostics> diagnostics(WindowId windowId) {
        WindowRuntime windowRuntime = windows.get(windowId);
        if (windowRuntime == null) {
            return CompletableFuture.failedFuture(
                    new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window"));
        }
        return platformApp.ui().submit(windowRuntime.window.webView()::diagnostics);
    }
    public CompletionStage<Boolean> devToolsEnabled(WindowId windowId) {
        WindowRuntime runtime=windows.get(windowId);if(runtime==null)return CompletableFuture.failedFuture(
                new JDeskException(ErrorCode.WINDOW_CLOSED,"Unknown or closed window"));
        return platformApp.ui().submit(runtime.window.webView()::devToolsEnabled);
    }

    /** UI dispatcher of the running platform application; null before {@link #run()}. */
    @Override
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
            windowIds.remove(windowId);
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
        windowIds.clear();
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

    @Override
    public void requestStop() {
        close();
    }
}
