package dev.jdesk.runtime.boot;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.EventEmitter;
import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.api.FileWatchHandle;
import dev.jdesk.api.FileWatchOptions;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.PtyHandle;
import dev.jdesk.api.PtySpec;
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
import dev.jdesk.runtime.pty.PtyManager;
import dev.jdesk.runtime.watch.FileWatchManager;
import dev.jdesk.runtime.watch.PortableWatchBackend;
import dev.jdesk.webview.spi.FileWatchBackend;
import dev.jdesk.webview.spi.PtyBackend;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    /** Forwarded page console output; its own logger name so apps can filter/route it. */
    private static final Logger PAGE_CONSOLE = System.getLogger("dev.jdesk.webview.console");
    public static final String APP_ORIGIN = "jdesk://app";

    private static Level consoleLevel(String level) {
        return switch (level) {
            case "error" -> Level.ERROR;
            case "warn" -> Level.WARNING;
            case "debug" -> Level.DEBUG;
            default -> Level.INFO;
        };
    }

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
    private final WindowStateStore windowStateStore;
    private PlatformApplication platformApp;
    private FileWatchManager fileWatchManager;
    private PtyManager ptyManager;
    private final Set<dev.jdesk.webview.spi.TrayControl> trayItems = ConcurrentHashMap.newKeySet();
    private volatile AutomationServer automationServer;

    /** Per-window wiring: dispatcher plus the tracked current top-level origin. */
    private final class WindowRuntime implements WindowHandle {
        final PlatformWindow window;
        final CommandDispatcher dispatcher;
        final boolean rememberBounds;
        final int minWidth;
        final int minHeight;
        volatile String currentOrigin;

        WindowRuntime(PlatformWindow window, CommandDispatcher dispatcher, String initialOrigin,
                boolean rememberBounds, int minWidth, int minHeight) {
            this.window = window;
            this.dispatcher = dispatcher;
            this.currentOrigin = initialOrigin;
            this.rememberBounds = rememberBounds;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
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
            // The configured minimum applies to programmatic resizes too, so a window
            // can never end up smaller than its declared minimum on any path.
            int clampedWidth = Math.max(width, minWidth);
            int clampedHeight = Math.max(height, minHeight);
            return controlWindow(id(), w -> w.setBounds(
                    new dev.jdesk.webview.spi.WindowBounds(x, y, clampedWidth, clampedHeight)));
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
        @Override public CompletionStage<Void> print() {
            return controlWindow(id(), PlatformWindow::print);
        }
        @Override public CompletionStage<java.util.Optional<String>> showContextMenu(
                dev.jdesk.api.MenuSpec menu) {
            java.util.Objects.requireNonNull(menu, "menu");
            return platformApp.ui().submit(() -> window.showContextMenu(menu));
        }
        @Override public CompletionStage<dev.jdesk.api.Subscription> onFileDrop(
                Consumer<List<java.nio.file.Path>> listener) {
            java.util.Objects.requireNonNull(listener, "listener");
            return platformApp.ui().submit(() -> {
                Runnable unsubscribe = window.onFileDrop(listener);
                return (dev.jdesk.api.Subscription) () ->
                        platformApp.ui().submit(() -> { unsubscribe.run(); return null; });
            });
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
        this.windowStateStore = new WindowStateStore(spec.id());

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
                options.assetSource(), options.spaFallback(), options.securityHeaders(),
                spec.assetRoutes());
        Optional<String> devOrigin = options.devMode()
                ? spec.devServerUrl().map(OriginNormalizer::normalize)
                : Optional.empty();
        platformApp = provider.createApplication(new PlatformApplicationConfig(
                spec.id(), options.devMode(), devOrigin, assetResolver));
        DevAssetWatcher assetWatcher = null;
        try {
            // Start automation before any window exists so console output emitted
            // during the initial page load is already captured in its buffer.
            automationServer = AutomationServer.startIfEnabled(this, spec.id()).orElse(null);
            for (WindowConfig config : spec.windows()) {
                windowIds.add(config.id());
                createWindowOnUiThread(config);
            }
            // Dev-mode edit-and-reload for directory-served assets: any change under the
            // asset root reloads every window (no bundler or dev server required).
            if (options.devMode()
                    && options.assetSource() instanceof dev.jdesk.runtime.assets.DirectoryAssetSource dir) {
                assetWatcher = DevAssetWatcher.start(dir.root(), this::reloadAllWindows);
            }
            lifecycle.ready(this);
            platformApp.runEventLoop();
        } finally {
            if (automationServer != null) {
                automationServer.close();
            }
            if (assetWatcher != null) {
                assetWatcher.close();
            }
            shutdown();
        }
        return 0;
    }

    /** Ids of currently open windows (automation endpoint). */
    Set<WindowId> openWindowIds() {
        return Set.copyOf(windows.keySet());
    }

    private void reloadAllWindows() {
        LOG.log(Level.INFO, "Asset change detected; reloading {0} window(s)", windows.size());
        for (WindowRuntime windowRuntime : windows.values()) {
            platformApp.ui().submit(() -> {
                windowRuntime.window.webView().evaluate("location.reload()");
                return null;
            });
        }
    }

    private void createWindowOnUiThread(WindowConfig config) {
        WindowConfig launchConfig = devWindowConfig(config);
        boolean logConsole = options.devMode() || Boolean.getBoolean("jdesk.console.forward");
        boolean automation = Boolean.getBoolean("jdesk.automation");
        PlatformWindow window = platformApp.createWindow(new NativeWindowConfig(
                launchConfig.id(), launchConfig.title(), launchConfig.width(), launchConfig.height(),
                launchConfig.resizable(), launchConfig.entry(), options.devMode(),
                launchConfig.minWidth(), launchConfig.minHeight(),
                logConsole || automation));
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

            if (logConsole || automation) {
                dispatcher.onConsole((level, message) -> {
                    if (logConsole) {
                        PAGE_CONSOLE.log(consoleLevel(level), "[{0}] {1}",
                                launchConfig.id(), message);
                    }
                    AutomationServer automationSink = automationServer;
                    if (automationSink != null) {
                        automationSink.recordConsole(launchConfig.id(), level, message);
                    }
                });
            }

            String initialOrigin = originOfEntry(launchConfig);
            WindowRuntime windowRuntime = new WindowRuntime(window, dispatcher, initialOrigin,
                    launchConfig.rememberBounds(), launchConfig.minWidth(),
                    launchConfig.minHeight());
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
            window.onCloseRequested(() -> {
                if (launchConfig.rememberBounds()) {
                    saveBoundsQuietly(launchConfig.id(), window);
                }
                return lifecycle.closeRequested(launchConfig.id());
            });
            window.onClosed(() -> windowClosed(launchConfig.id()));
            window.webView().navigate(launchConfig.entry());
            window.show();
            // Bounds are applied AFTER show() — on macOS setting the frame before the
            // window is ordered in does not stick. Precedence: remembered bounds win;
            // otherwise an explicit configured position places the window; otherwise the
            // OS centers it.
            boolean placed = false;
            if (launchConfig.rememberBounds()) {
                // Clamp restored bounds to the declared minimum: state saved by an
                // older version (smaller minimum) must not undercut this run's floor.
                java.util.Optional<dev.jdesk.webview.spi.WindowBounds> restored =
                        windowStateStore.load(launchConfig.id());
                if (restored.isPresent()) {
                    dev.jdesk.webview.spi.WindowBounds saved = restored.get();
                    window.setBounds(new dev.jdesk.webview.spi.WindowBounds(
                            saved.x(), saved.y(),
                            Math.max(saved.width(), launchConfig.minWidth()),
                            Math.max(saved.height(), launchConfig.minHeight())));
                    placed = true;
                }
            }
            if (!placed && launchConfig.position().isPresent()) {
                WindowConfig.Position p = launchConfig.position().get();
                window.setBounds(new dev.jdesk.webview.spi.WindowBounds(
                        p.x(), p.y(), launchConfig.width(), launchConfig.height()));
            }
            if (launchConfig.startMaximized()) {
                window.setMaximized(true);
            }
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
                config.resizable(), java.net.URI.create(spec.devServerUrl().get()),
                config.minWidth(), config.minHeight(), config.startMaximized(),
                config.rememberBounds(), config.position());
    }

    /** Best-effort bounds persistence; never lets state I/O affect closing. */
    private void saveBoundsQuietly(WindowId windowId, PlatformWindow window) {
        try {
            windowStateStore.save(windowId, window.getBounds());
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Window bounds not saved for {0}", windowId, e);
        }
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

    private dev.jdesk.api.SecretStore secretStore;

    @Override
    public synchronized dev.jdesk.api.SecretStore secrets() {
        // One instance per runtime: platform stores synchronize their file access on
        // the instance, so racing first callers must not each get their own copy.
        if (secretStore == null) {
            secretStore = platformApp.secrets(spec.id());
        }
        return secretStore;
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
    @Override public CompletionStage<dev.jdesk.api.SystemTheme> systemTheme() {
        return platformApp.ui().submit(platformApp::systemTheme);
    }
    @Override public CompletionStage<Optional<byte[]>> readClipboard(String type) {
        java.util.Objects.requireNonNull(type, "type");
        return platformApp.ui().submit(() -> Optional.ofNullable(platformApp.readClipboard(type)));
    }
    @Override public CompletionStage<Void> writeClipboard(String type, byte[] data) {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(data, "data");
        if (data.length > 64L * 1024 * 1024) {
            return CompletableFuture.failedFuture(new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Clipboard data exceeds 64 MiB"));
        }
        byte[] copy = data.clone();
        return platformApp.ui().submit(() -> { platformApp.writeClipboard(type, copy); return null; });
    }
    @Override public CompletionStage<Void> setDockBadge(String label) {
        return platformApp.ui().submit(() -> { platformApp.setDockBadge(label); return null; });
    }
    @Override public CompletionStage<Void> setApplicationMenu(
            dev.jdesk.api.MenuSpec menu, Consumer<String> onAction) {
        java.util.Objects.requireNonNull(menu, "menu");
        java.util.Objects.requireNonNull(onAction, "onAction");
        return platformApp.ui().submit(() -> { platformApp.setApplicationMenu(menu, onAction); return null; });
    }
    @Override public CompletionStage<Void> setApplicationIcon(byte[] pngData) {
        java.util.Objects.requireNonNull(pngData, "pngData");
        byte[] copy = pngData.clone();
        return platformApp.ui().submit(() -> { platformApp.setApplicationIcon(copy); return null; });
    }
    @Override public CompletionStage<dev.jdesk.api.TrayHandle> createTrayItem(
            dev.jdesk.api.TraySpec spec, Consumer<String> onAction) {
        java.util.Objects.requireNonNull(spec, "spec");
        java.util.Objects.requireNonNull(onAction, "onAction");
        return platformApp.ui().submit(() -> {
            dev.jdesk.webview.spi.TrayControl control = platformApp.createTrayItem(spec, onAction);
            trayItems.add(control);
            return trayHandle(control);
        });
    }

    @Override public CompletionStage<dev.jdesk.api.Subscription> registerGlobalShortcut(
            String accelerator, Runnable callback) {
        java.util.Objects.requireNonNull(accelerator, "accelerator");
        java.util.Objects.requireNonNull(callback, "callback");
        return platformApp.ui().submit(() -> {
            Runnable unregister = platformApp.registerGlobalShortcut(accelerator, callback);
            return (dev.jdesk.api.Subscription) () ->
                    platformApp.ui().submit(() -> { unregister.run(); return null; });
        });
    }

    @Override public CompletionStage<Void> showNotification(String title, String body) {
        return platformApp.ui().submit(() -> { platformApp.showNotification(title, body); return null; });
    }

    private dev.jdesk.api.TrayHandle trayHandle(dev.jdesk.webview.spi.TrayControl control) {
        return new dev.jdesk.api.TrayHandle() {
            @Override public void setTitle(String title) {
                platformApp.ui().submit(() -> { control.setTitle(title); return null; });
            }
            @Override public void close() {
                if (trayItems.remove(control)) {
                    platformApp.ui().submit(() -> { control.remove(); return null; });
                }
            }
        };
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
    @Override public CompletionStage<dev.jdesk.api.FileDialogResult> showOpenDialog(
            dev.jdesk.api.FileDialog.OpenDialog dialog) {
        java.util.Objects.requireNonNull(dialog, "dialog");
        return platformApp.ui().submit(() -> platformApp.showOpenDialog(dialog));
    }
    @Override public CompletionStage<dev.jdesk.api.FileDialogResult> showSaveDialog(
            dev.jdesk.api.FileDialog.SaveDialog dialog) {
        java.util.Objects.requireNonNull(dialog, "dialog");
        return platformApp.ui().submit(() -> platformApp.showSaveDialog(dialog));
    }
    @Override public CompletionStage<Void> printFile(dev.jdesk.api.PrintJob job) {
        java.util.Objects.requireNonNull(job, "job");
        // Validate the file once, platform-agnostically, so every backend rejects a
        // missing/unreadable file with the same INVALID_REQUEST (Windows ShellExecute
        // would otherwise surface an opaque error code instead).
        if (!java.nio.file.Files.isReadable(java.nio.file.Path.of(job.filePath()))) {
            return CompletableFuture.failedFuture(new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Print file does not exist or is not readable"));
        }
        // Printing shells out to the OS spooler (blocking) — run it on a virtual thread,
        // never the common ForkJoinPool where a blocked lp would starve other work.
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.ofVirtual().name("jdesk-print").start(() -> {
            try {
                platformApp.printFile(job);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public FileWatchHandle watchFiles(Path root, FileWatchOptions options,
            Consumer<List<FileWatchEvent>> listener) {
        return fileWatchManager().watch(root, options, listener);
    }

    /** Lazily builds the manager on the platform's native backend, else the portable one. */
    private synchronized FileWatchManager fileWatchManager() {
        if (fileWatchManager == null) {
            if (platformApp == null) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Application is not started");
            }
            FileWatchBackend backend = platformApp.fileWatchBackend()
                    .orElseGet(PortableWatchBackend::new);
            fileWatchManager = new FileWatchManager(backend);
        }
        return fileWatchManager;
    }

    @Override
    public PtyHandle openPty(PtySpec spec, Consumer<byte[]> output) {
        return ptyManager().open(spec, output);
    }

    private synchronized PtyManager ptyManager() {
        if (ptyManager == null) {
            if (platformApp == null) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Application is not started");
            }
            PtyBackend backend = platformApp.ptyBackend().orElseThrow(() ->
                    new JDeskException(ErrorCode.ILLEGAL_STATE,
                            "Pseudo-terminals are not supported by this platform adapter"));
            ptyManager = new PtyManager(backend);
        }
        return ptyManager;
    }

    /** Closes one window from any thread; the native close runs on the UI thread. */
    public CompletionStage<Void> closeWindow(WindowId windowId) {
        WindowRuntime windowRuntime = windows.get(windowId);
        if (windowRuntime == null) {
            return CompletableFuture.failedFuture(
                    new JDeskException(ErrorCode.WINDOW_CLOSED, "Unknown or closed window"));
        }
        return platformApp.ui().submit(() -> {
            if (windowRuntime.rememberBounds) {
                saveBoundsQuietly(windowId, windowRuntime.window);
            }
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
                if (windowRuntime.rememberBounds) {
                    saveBoundsQuietly(windowRuntime.window.id(), windowRuntime.window);
                }
                windowRuntime.dispatcher.close();
                windowRuntime.window.close();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Window shutdown failed", e);
            }
        }
        FileWatchManager watchManager;
        PtyManager ptys;
        synchronized (this) {
            watchManager = fileWatchManager;
            fileWatchManager = null;
            ptys = ptyManager;
            ptyManager = null;
        }
        if (watchManager != null) {
            try {
                watchManager.close();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "File watch shutdown failed", e);
            }
        }
        if (ptys != null) {
            try {
                ptys.close();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "PTY shutdown failed", e);
            }
        }
        for (dev.jdesk.webview.spi.TrayControl tray : trayItems) {
            try {
                tray.remove(); // shutdown runs on the UI thread
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Tray removal failed", e);
            }
        }
        trayItems.clear();
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
