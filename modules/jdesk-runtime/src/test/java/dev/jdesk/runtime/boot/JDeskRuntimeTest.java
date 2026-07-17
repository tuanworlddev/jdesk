package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.LifecycleState;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.Subscription;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowHandle;
import dev.jdesk.api.WindowId;
import dev.jdesk.api.WebViewDataType;
import dev.jdesk.runtime.assets.MapAssetSource;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.NavigationListener;
import dev.jdesk.webview.spi.NavigationRequest;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;
import dev.jdesk.webview.spi.PlatformWebView;
import dev.jdesk.webview.spi.PlatformWindow;
import dev.jdesk.webview.spi.WebViewDiagnostics;
import dev.jdesk.webview.spi.WebViewSnapshot;
import dev.jdesk.webview.spi.WebViewProcessFailure;
import dev.jdesk.webview.spi.WindowBounds;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runtime wiring over an in-test fake platform (unit tests may use fakes, spec 17.1).
 * Proves lifecycle ordering, window creation and navigation, navigation policy wiring,
 * window-close bookkeeping, and the close-veto path — all platform-agnostic.
 */
@Timeout(30)
class JDeskRuntimeTest {

    private static final String ENTRY = "jdesk://app/index.html";

    // ------------------------------------------------------------------ fakes

    static final class FakeUiDispatcher implements UiDispatcher {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-ui-thread");
            t.setDaemon(true);
            return t;
        });
        private final Thread uiThread;

        FakeUiDispatcher() {
            try {
                uiThread = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public boolean isUiThread() {
            return Thread.currentThread() == uiThread;
        }

        @Override
        public void execute(Runnable action) {
            if (isUiThread()) {
                action.run();
            } else {
                executor.execute(action);
            }
        }

        @Override
        public <T> CompletionStage<T> submit(Callable<T> action) {
            CompletableFuture<T> future = new CompletableFuture<>();
            execute(() -> {
                try {
                    future.complete(action.call());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        }

        @Override
        public void assertUiThread() {
            if (!isUiThread()) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Not on the UI thread");
            }
        }

        void shutdown() {
            executor.shutdown();
        }
    }

    static final class FakeWebView implements PlatformWebView {
        final List<URI> navigations = new CopyOnWriteArrayList<>();
        final List<String> posted = new CopyOnWriteArrayList<>();
        volatile Consumer<String> messageListener;
        volatile NavigationListener navigationListener;
        volatile Consumer<URI> committedListener;
        volatile Consumer<WebViewProcessFailure> failureListener;
        volatile Set<WebViewDataType> clearedData = Set.of();
        volatile boolean closed;

        @Override
        public void navigate(URI uri) {
            navigations.add(uri);
        }

        @Override
        public void postJson(String json) {
            posted.add(json);
        }

        @Override
        public CompletionStage<String> evaluate(String script) {
            return CompletableFuture.completedFuture("");
        }

        @Override
        public Subscription onMessage(Consumer<String> listener) {
            this.messageListener = listener;
            return () -> this.messageListener = null;
        }

        @Override
        public Subscription onNavigation(NavigationListener listener) {
            this.navigationListener = listener;
            return () -> this.navigationListener = null;
        }

        @Override
        public Subscription onNavigationCommitted(Consumer<URI> listener) {
            this.committedListener = listener;
            return () -> this.committedListener = null;
        }

        @Override
        public Subscription onProcessFailure(Consumer<WebViewProcessFailure> listener) {
            this.failureListener = listener;
            return () -> this.failureListener = null;
        }

        void simulateProcessFailure() {
            assertThat(failureListener).as("runtime must register a failure listener").isNotNull();
            failureListener.accept(new WebViewProcessFailure(
                    WebViewProcessFailure.Kind.RENDER_PROCESS_EXITED, "test renderer crash"));
        }

        /** Simulates a committed main-frame document, as real engines report it. */
        void simulateCommitted(URI uri) {
            Consumer<URI> listener = committedListener;
            assertThat(listener).as("runtime must register a committed listener").isNotNull();
            listener.accept(uri);
        }

        @Override
        public CompletionStage<WebViewSnapshot> snapshot() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("fake"));
        }

        @Override
        public WebViewDiagnostics diagnostics() {
            return new WebViewDiagnostics(Optional.empty(), Optional.empty(), Optional.empty());
        }
        @Override public boolean devToolsEnabled(){return false;}

        @Override
        public CompletionStage<Void> clearData(Set<WebViewDataType> dataTypes) {
            clearedData = Set.copyOf(dataTypes);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        /** Simulates a JSON envelope arriving from the page. */
        void simulateIncoming(String raw) {
            Consumer<String> listener = messageListener;
            assertThat(listener).as("runtime must register a message listener").isNotNull();
            listener.accept(raw);
        }

        /** Simulates the engine asking whether a navigation may proceed. */
        NavigationDecision simulateNavigation(NavigationRequest request) {
            NavigationListener listener = navigationListener;
            assertThat(listener).as("runtime must register a navigation listener").isNotNull();
            return listener.onNavigate(request);
        }
    }

    static final class FakeWindow implements PlatformWindow {
        final NativeWindowConfig config;
        final FakeWebView webView = new FakeWebView();
        volatile boolean shown;
        volatile boolean closed;
        volatile WindowBounds lastBounds;
        volatile boolean boundsSetAfterShow;
        volatile java.util.function.BooleanSupplier closeRequestedHandler;
        volatile Runnable closedHandler;

        @Override
        public Subscription onCloseRequested(java.util.function.BooleanSupplier handler) {
            this.closeRequestedHandler = handler;
            return () -> this.closeRequestedHandler = null;
        }

        @Override
        public Subscription onClosed(Runnable handler) {
            this.closedHandler = handler;
            return () -> this.closedHandler = null;
        }

        FakeWindow(NativeWindowConfig config) {
            this.config = config;
        }

        @Override
        public WindowId id() {
            return config.id();
        }

        @Override
        public PlatformWebView webView() {
            return webView;
        }

        @Override
        public void show() {
            shown = true;
        }

        @Override
        public void hide() {
        }

        @Override public void focus() { }
        @Override public void setMinimized(boolean value) { }
        @Override public void setMaximized(boolean value) { }
        @Override public void setFullscreen(boolean value) { }
        @Override public void setAlwaysOnTop(boolean value) { }

        @Override
        public void setTitle(String title) {
        }

        @Override
        public void setBounds(WindowBounds bounds) {
            this.lastBounds = bounds;
            this.boundsSetAfterShow = shown;
        }

        @Override
        public WindowBounds getBounds() {
            return new WindowBounds(0, 0, config.width(), config.height());
        }

        volatile java.util.function.Consumer<Boolean> focusListener;
        @Override
        public Runnable onFocusChanged(java.util.function.Consumer<Boolean> listener) {
            this.focusListener = listener;
            return () -> this.focusListener = null;
        }
        void simulateFocus(boolean focused) {
            java.util.function.Consumer<Boolean> l = focusListener;
            if (l != null) {
                l.accept(focused);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class FakeApp implements PlatformApplication {
        final FakeUiDispatcher ui = new FakeUiDispatcher();
        final List<FakeWindow> windows = new CopyOnWriteArrayList<>();
        final CountDownLatch eventLoopEntered = new CountDownLatch(1);
        final CountDownLatch stopRequested = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final List<URI> externalUris = new CopyOnWriteArrayList<>();

        @Override
        public UiDispatcher ui() {
            return ui;
        }

        @Override
        public PlatformWindow createWindow(NativeWindowConfig config) {
            FakeWindow window = new FakeWindow(config);
            windows.add(window);
            return window;
        }

        @Override public void openExternal(URI uri) { externalUris.add(uri); }
        @Override public String readClipboardText() { return "clipboard"; }
        @Override public void writeClipboardText(String text) { }
        @Override public dev.jdesk.api.MessageDialogResult showMessageDialog(
                dev.jdesk.api.MessageDialog dialog) {
            return new dev.jdesk.api.MessageDialogResult(0, dialog.buttons().getFirst());
        }

        @Override
        public void runEventLoop() {
            eventLoopEntered.countDown();
            try {
                stopRequested.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void requestStop() {
            stopRequested.countDown();
        }

        @Override
        public void close() {
            closed.set(true);
            ui.shutdown();
        }

        // --- GAP-004 desktop integration (recording fakes) ---
        volatile dev.jdesk.api.SystemTheme theme = dev.jdesk.api.SystemTheme.DARK;
        final java.util.Map<String, byte[]> binaryClipboard =
                new java.util.concurrent.ConcurrentHashMap<>();
        volatile String dockBadge;
        volatile dev.jdesk.api.MenuSpec appMenu;
        volatile byte[] appIcon;
        final List<String> notifications = new CopyOnWriteArrayList<>();
        final List<String> registeredShortcuts = new CopyOnWriteArrayList<>();
        final AtomicInteger shortcutUnregisters = new AtomicInteger();
        final List<String> trayEvents = new CopyOnWriteArrayList<>();
        final AtomicInteger trayRemovals = new AtomicInteger();
        final List<String> printedFiles = new CopyOnWriteArrayList<>();

        @Override public void printFile(dev.jdesk.api.PrintJob job) {
            printedFiles.add(job.filePath());
        }

        volatile dev.jdesk.api.ShareContent sharedContent;
        @Override public boolean share(dev.jdesk.api.ShareContent content) {
            this.sharedContent = content;
            return true;
        }
        @Override public boolean biometricsAvailable() {
            return true;
        }

        @Override public dev.jdesk.api.SystemTheme systemTheme() { return theme; }
        @Override public byte[] readClipboard(String type) { return binaryClipboard.get(type); }
        @Override public void writeClipboard(String type, byte[] data) {
            binaryClipboard.put(type, data.clone());
        }
        @Override public void setDockBadge(String label) { this.dockBadge = label; }
        @Override public void setApplicationMenu(dev.jdesk.api.MenuSpec menu,
                Consumer<String> onAction) { this.appMenu = menu; }
        @Override public void setApplicationIcon(byte[] pngData) { this.appIcon = pngData.clone(); }
        @Override public void showNotification(String title, String body) {
            notifications.add(title + ":" + body);
        }
        @Override public Runnable registerGlobalShortcut(String accelerator, Runnable callback) {
            registeredShortcuts.add(accelerator);
            return shortcutUnregisters::incrementAndGet;
        }
        @Override public dev.jdesk.webview.spi.TrayControl createTrayItem(
                dev.jdesk.api.TraySpec spec, Consumer<String> onAction) {
            trayEvents.add("create:" + spec.title());
            return new dev.jdesk.webview.spi.TrayControl() {
                @Override public void setTitle(String title) { trayEvents.add("title:" + title); }
                @Override public void remove() { trayRemovals.incrementAndGet(); }
            };
        }
        @Override public dev.jdesk.api.SecretStore secrets(String applicationId) {
            return new dev.jdesk.api.SecretStore() {
                final java.util.Map<String, String> values =
                        new java.util.concurrent.ConcurrentHashMap<>();
                @Override public Optional<String> get(String key) {
                    return Optional.ofNullable(values.get(key));
                }
                @Override public void put(String key, String value) { values.put(key, value); }
                @Override public void delete(String key) { values.remove(key); }
            };
        }

        boolean stopWasRequested() {
            return stopRequested.getCount() == 0;
        }
    }

    static final class FakeProvider implements PlatformProvider {
        volatile FakeApp app;
        volatile PlatformApplicationConfig config;

        @Override
        public String id() {
            return "unit-test-fake";
        }

        @Override
        public PlatformInfo info() {
            return new PlatformInfo("fake-os", "1.0", "fake-arch");
        }

        @Override
        public PlatformApplication createApplication(PlatformApplicationConfig config) {
            this.config = config;
            this.app = new FakeApp();
            return app;
        }
    }

    static final class RecordingLifecycleListener implements LifecycleListener {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean allowClose = new AtomicBoolean(true);
        volatile ApplicationHandle readyHandle;

        @Override
        public void onStarting() {
            calls.add("starting");
        }

        @Override
        public void onReady() {
            calls.add("ready");
        }

        @Override
        public void onReady(ApplicationHandle application) {
            readyHandle = application;
            onReady();
        }

        @Override
        public boolean onCloseRequested(WindowId windowId) {
            calls.add("closeRequested:" + windowId);
            return allowClose.get();
        }

        @Override
        public void onStopping() {
            calls.add("stopping");
        }

        @Override
        public void onStopped() {
            calls.add("stopped");
        }
    }

    // -------------------------------------------------------------- harness

    /** Runs a JDeskRuntime on a background thread and coordinates start/stop. */
    static final class RunningRuntime implements AutoCloseable {
        final FakeProvider provider = new FakeProvider();
        final RecordingLifecycleListener listener = new RecordingLifecycleListener();
        final JDeskRuntime runtime;
        final Thread thread;
        final AtomicInteger exitCode = new AtomicInteger(-1);

        RunningRuntime(List<WindowConfig> windowConfigs) {
            this(windowConfigs, Optional.empty(), false);
        }

        RunningRuntime(List<WindowConfig> windowConfigs, Optional<String> devUrl, boolean devMode) {
            ApplicationSpec spec = new ApplicationSpec(
                    "dev.jdesk.test",
                    CommandRegistry.of(),
                    CapabilitySet.empty(),
                    windowConfigs,
                    List.of(listener),
                    devUrl);
            RuntimeOptions production = RuntimeOptions.production(new MapAssetSource());
            RuntimeOptions options = new RuntimeOptions(devMode, production.assetSource(),
                    production.spaFallback(), production.securityHeaders(), production.limits(),
                    production.overflowPolicy(), production.navigationGrace());
            runtime = new JDeskRuntime(spec, provider, options);
            thread = new Thread(() -> exitCode.set(runtime.run()), "jdesk-runtime-test");
            thread.start();
        }

        RunningRuntime(List<WindowConfig> windowConfigs, boolean singleInstance,
                Consumer<List<String>> activationHandler, List<String> launchArguments) {
            ApplicationSpec spec = new ApplicationSpec(
                    "dev.jdesk.test",
                    CommandRegistry.of(),
                    CapabilitySet.empty(),
                    windowConfigs,
                    List.of(listener),
                    Optional.empty(),
                    CommandRegistry.of(),
                    singleInstance,
                    activationHandler);
            RuntimeOptions production = RuntimeOptions.production(new MapAssetSource());
            RuntimeOptions options = new RuntimeOptions(false, production.assetSource(),
                    production.spaFallback(), production.securityHeaders(), production.limits(),
                    production.overflowPolicy(), production.navigationGrace());
            runtime = new JDeskRuntime(spec, provider, options, activationHandler, launchArguments);
            thread = new Thread(() -> exitCode.set(runtime.run()), "jdesk-runtime-test");
            thread.start();
        }

        static WindowConfig window(String id) {
            return WindowConfig.builder().id(id).title("t").entry(ENTRY).build();
        }

        RunningRuntime awaitReady() throws InterruptedException {
            assertThat(waitForApp().eventLoopEntered.await(10, TimeUnit.SECONDS))
                    .as("runEventLoop must be entered")
                    .isTrue();
            return this;
        }

        FakeApp waitForApp() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (provider.app == null && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(provider.app).as("platform application must be created").isNotNull();
            return provider.app;
        }

        void joinRun() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(thread.isAlive()).as("run() must return").isFalse();
        }

        void stopAndJoin() throws InterruptedException {
            provider.app.requestStop();
            joinRun();
        }

        @Override
        public void close() {
            if (thread.isAlive()) {
                if (provider.app != null) {
                    provider.app.requestStop();
                }
                try {
                    thread.join(TimeUnit.SECONDS.toMillis(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while stopping test runtime", e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void windowFocusChangesReachTheListener() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            java.util.List<Boolean> events = new CopyOnWriteArrayList<>();
            running.runtime.window(new WindowId("main")).orElseThrow()
                    .onFocusChanged(events::add).toCompletableFuture().get(5, TimeUnit.SECONDS);
            FakeWindow window = running.provider.app.windows.getFirst();
            window.simulateFocus(true);
            window.simulateFocus(false);
            assertThat(events).containsExactly(true, false);
            running.stopAndJoin();
        }
    }

    @Test
    void shareAndBiometricsDelegateToThePlatform() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            assertThat(running.runtime.share(dev.jdesk.api.ShareContent.text("share me"))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(running.provider.app.sharedContent.text()).isEqualTo("share me");
            assertThat(running.runtime.biometricsAvailable()
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
            running.stopAndJoin();
        }
    }

    @Test
    void interactiveNotificationFallsBackToBasicDeliveryAndCompletes() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            dev.jdesk.api.InteractiveNotification notification =
                    dev.jdesk.api.InteractiveNotification.of("Build done", "3 tests passed")
                            .withActions(new dev.jdesk.api.InteractiveNotification.Action(
                                    "open", "Open"))
                            .withReply("Comment");
            // The fake adapter has no action support, so the SPI default delivers title+body and
            // completes as dismissed — the app still gets a well-formed response.
            dev.jdesk.api.NotificationResponse response = running.runtime
                    .showNotification(notification).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(response.actionId()).isEmpty();
            assertThat(running.provider.app.notifications).contains("Build done:3 tests passed");
            running.stopAndJoin();
        }
    }

    @Test
    void coldStartArgumentsReachActivationHandlerForSingleInstance() throws Exception {
        java.util.List<String> received = new CopyOnWriteArrayList<>();
        try (RunningRuntime running = new RunningRuntime(
                List.of(RunningRuntime.window("main")), true,
                args -> received.addAll(args), List.of("dev.example://open?id=7"))) {
            running.awaitReady();
            // The cold-start argv is dispatched right after ready — a fresh launch carrying a
            // deep-link URL reaches the same handler as a warm single-instance activation.
            assertThat(received).containsExactly("dev.example://open?id=7");
            running.stopAndJoin();
        }
    }

    @Test
    void coldStartArgumentsIgnoredWhenNotSingleInstance() throws Exception {
        java.util.List<String> received = new CopyOnWriteArrayList<>();
        try (RunningRuntime running = new RunningRuntime(
                List.of(RunningRuntime.window("main")), false,
                args -> received.addAll(args), List.of("dev.example://x"))) {
            running.awaitReady();
            // Without single-instance there is no activation contract; argv stays in main().
            assertThat(received).isEmpty();
            running.stopAndJoin();
        }
    }

    @Test
    void configuredPositionAppliesBoundsAfterShow() throws Exception {
        WindowConfig positioned = WindowConfig.builder().id("main").title("t").entry(ENTRY)
                .size(360, 260).position(170, 140).build();
        try (RunningRuntime running = new RunningRuntime(List.of(positioned))) {
            running.awaitReady();
            FakeWindow window = running.provider.app.windows.getFirst();
            // Position placed the window at the requested top-left with the configured size.
            assertThat(window.lastBounds)
                    .isEqualTo(new WindowBounds(170, 140, 360, 260));
            // Applied after show(): on macOS setting the frame before ordering-in is lost.
            assertThat(window.boundsSetAfterShow).isTrue();
        }
    }

    @Test
    void nativeDesktopIntegrationDelegatesToThePlatform() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            ApplicationHandle app = running.listener.readyHandle;
            FakeApp fake = running.provider.app;

            // theme + text/binary clipboard (binary is copied in and back out)
            assertThat(app.systemTheme().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isEqualTo(dev.jdesk.api.SystemTheme.DARK);
            assertThat(app.readClipboardText().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isEqualTo("clipboard");
            app.writeClipboardText("hi").toCompletableFuture().get(5, TimeUnit.SECONDS);
            app.writeClipboard("app/x", new byte[] {1, 2, 3})
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            Optional<byte[]> roundTrip =
                    app.readClipboard("app/x").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(roundTrip).isPresent();
            assertThat(roundTrip.get()).containsExactly(1, 2, 3);
            assertThat(app.readClipboard("absent").toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isEmpty();

            // dock badge, application menu, application icon
            app.setDockBadge("7").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fake.dockBadge).isEqualTo("7");
            app.setApplicationMenu(dev.jdesk.api.MenuSpec.of(
                            dev.jdesk.api.MenuItem.action("q", "Quit")), id -> { })
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fake.appMenu).isNotNull();
            app.setApplicationIcon(new byte[] {9}).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fake.appIcon).containsExactly(9);

            // desktop notification
            app.showNotification("Title", "Body").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fake.notifications).containsExactly("Title:Body");

            // global shortcut register then unsubscribe (unsubscribe marshals to the UI thread)
            Subscription shortcut = app.registerGlobalShortcut("CmdOrCtrl+K", () -> { })
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fake.registeredShortcuts).containsExactly("CmdOrCtrl+K");
            shortcut.close();
            app.systemTheme().toCompletableFuture().get(5, TimeUnit.SECONDS); // drain UI submits
            assertThat(fake.shortcutUnregisters.get()).isEqualTo(1);

            // tray create + title update + close
            dev.jdesk.api.TrayHandle tray = app.createTrayItem(dev.jdesk.api.TraySpec.of("Tray",
                            dev.jdesk.api.MenuSpec.of(dev.jdesk.api.MenuItem.action("q", "Quit"))),
                            id -> { })
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            tray.setTitle("Updated");
            tray.close();
            app.systemTheme().toCompletableFuture().get(5, TimeUnit.SECONDS); // drain UI submits
            assertThat(fake.trayEvents).contains("create:Tray", "title:Updated");
            assertThat(fake.trayRemovals.get()).isEqualTo(1);

            // OS secret store round-trip
            dev.jdesk.api.SecretStore secrets = app.secrets();
            secrets.put("token", "abc");
            assertThat(secrets.get("token")).contains("abc");
            secrets.delete("token");
            assertThat(secrets.get("token")).isEmpty();
        }
    }

    @Test
    void printFileValidatesReadabilityThenDelegatesOffThread() throws Exception {
        java.nio.file.Path doc = java.nio.file.Files.createTempFile("jdesk-print", ".txt");
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            ApplicationHandle app = running.listener.readyHandle;
            FakeApp fake = running.provider.app;

            app.printFile(dev.jdesk.api.PrintJob.of(doc.toString()))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fake.printedFiles).containsExactly(doc.toString());

            // A missing/unreadable file is rejected platform-agnostically before any shell-out.
            assertThatThrownBy(() -> app.printFile(
                            dev.jdesk.api.PrintJob.of(doc.resolveSibling("gone.txt").toString()))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseInstanceOf(JDeskException.class);
        } finally {
            java.nio.file.Files.deleteIfExists(doc);
        }
    }

    @Test
    void runDrivesFullLifecycleAndWiresTheWindow() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();

            // Ready was reached before the event loop was entered.
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.READY);
            assertThat(running.listener.calls).startsWith("starting", "ready");
            assertThat(running.listener.readyHandle).isSameAs(running.runtime);
            assertThat(running.listener.readyHandle.window(new WindowId("main")))
                    .isPresent();

            FakeApp app = running.provider.app;
            assertThat(app.windows).hasSize(1);
            FakeWindow window = app.windows.getFirst();
            assertThat(window.config.id()).isEqualTo(new WindowId("main"));
            assertThat(window.webView.navigations).containsExactly(URI.create(ENTRY));
            assertThat(window.shown).isTrue();
            assertThat(running.provider.config.applicationId()).isEqualTo("dev.jdesk.test");
            assertThat(running.provider.config.devMode()).isFalse();
            assertThat(running.provider.config.devServerOrigin()).isEmpty();

            running.stopAndJoin();
            assertThat(running.exitCode.get()).isZero();
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.STOPPED);
            assertThat(running.listener.calls)
                    .containsExactly("starting", "ready", "stopping", "stopped");
            assertThat(app.closed.get()).isTrue();
            assertThat(window.closed).isTrue();
        }
    }

    @Test
    void devModeNavigatesWindowsToTheFrontendServer() throws Exception {
        String devUrl = "http://127.0.0.1:5173";
        try (RunningRuntime running = new RunningRuntime(
                List.of(RunningRuntime.window("main")), Optional.of(devUrl), true)) {
            running.awaitReady();

            FakeWindow window = running.provider.app.windows.getFirst();
            assertThat(window.config.entry()).isEqualTo(URI.create(devUrl));
            assertThat(window.webView.navigations).containsExactly(URI.create(devUrl));
            assertThat(running.provider.config.devServerOrigin()).contains(devUrl);
        }
    }

    @Test
    void malformedIncomingMessageDoesNotCrashTheRuntime() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            FakeWebView webView = running.provider.app.windows.getFirst().webView;

            webView.simulateIncoming("not json at all");
            webView.simulateIncoming("{\"type\":\"unknown\"}");
            webView.simulateIncoming("");

            // Still running and READY: malformed envelopes are dropped, never fatal.
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.READY);
            assertThat(running.thread.isAlive()).isTrue();

            running.stopAndJoin();
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.STOPPED);
        }
    }

    @Test
    void navigationPolicyIsWiredIntoTheWebView() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            FakeWebView webView = running.provider.app.windows.getFirst().webView;

            assertThat(webView.simulateNavigation(new NavigationRequest(
                    URI.create("https://evil.example/phish"), true, true)))
                    .isEqualTo(NavigationDecision.BLOCK);

            assertThat(webView.simulateNavigation(new NavigationRequest(
                    URI.create("jdesk://app/settings.html"), true, false)))
                    .isEqualTo(NavigationDecision.ALLOW);

            // Subframes are allowed but receive no native authority.
            assertThat(webView.simulateNavigation(new NavigationRequest(
                    URI.create("https://embedded.example/frame"), false, false)))
                    .isEqualTo(NavigationDecision.ALLOW);

            running.stopAndJoin();
        }
    }

    @Test
    void closingTheLastWindowStopsTheApplication() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            assertThat(running.provider.app.stopWasRequested()).isFalse();

            running.runtime.windowClosed(new WindowId("main"));

            assertThat(running.provider.app.stopWasRequested()).isTrue();
            running.joinRun();
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.STOPPED);
        }
    }

    @Test
    void emitterForUnknownWindowThrowsWindowClosed() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();

            assertThat(running.runtime.emitter(new WindowId("main"))).isNotNull();
            assertThatThrownBy(() -> running.runtime.emitter(new WindowId("never-existed")))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.WINDOW_CLOSED));

            running.stopAndJoin();

            // After shutdown the window is gone too.
            assertThatThrownBy(() -> running.runtime.emitter(new WindowId("main")))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.WINDOW_CLOSED));
        }
    }

    @Test
    void closeRequestedDelegatesToLifecycleListeners() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            WindowId main = new WindowId("main");

            running.listener.allowClose.set(false);
            assertThat(running.runtime.closeRequested(main)).isFalse();
            assertThat(running.listener.calls).contains("closeRequested:main");
            // Vetoed close leaves everything running.
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.READY);
            assertThat(running.thread.isAlive()).isTrue();

            running.listener.allowClose.set(true);
            assertThat(running.runtime.closeRequested(main)).isTrue();

            running.stopAndJoin();
        }
    }

    @Test
    void twoWindowsBothOpenAndStopOnlyAfterBothClose() throws Exception {
        try (RunningRuntime running = new RunningRuntime(
                List.of(RunningRuntime.window("main"), RunningRuntime.window("second")))) {
            running.awaitReady();
            FakeApp app = running.provider.app;
            assertThat(app.windows).hasSize(2);
            List<WindowId> ids = new ArrayList<>();
            for (FakeWindow window : app.windows) {
                ids.add(window.config.id());
                assertThat(window.webView.navigations).containsExactly(URI.create(ENTRY));
                assertThat(window.shown).isTrue();
            }
            assertThat(ids).containsExactlyInAnyOrder(new WindowId("main"), new WindowId("second"));

            running.runtime.windowClosed(new WindowId("main"));
            assertThat(app.stopWasRequested()).as("one window still open").isFalse();
            assertThat(running.thread.isAlive()).isTrue();
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.READY);
            // The closed window's dispatcher is gone.
            assertThatThrownBy(() -> running.runtime.emitter(new WindowId("main")))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.WINDOW_CLOSED));
            assertThat(running.runtime.emitter(new WindowId("second"))).isNotNull();

            running.runtime.windowClosed(new WindowId("second"));
            assertThat(app.stopWasRequested()).isTrue();
            running.joinRun();
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.STOPPED);
            assertThat(running.exitCode.get()).isZero();
        }
    }

    @Test
    void closeRequestsStopWithoutBlockingCaller() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            running.runtime.close(); // AutoCloseable path: requestStop
            running.joinRun();
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.STOPPED);
        }
    }

    @Test
    void concurrentOpenWindowCallsReserveTheIdBeforeUiCreation() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            WindowConfig duplicate = RunningRuntime.window("dynamic");

            CompletionStage<WindowHandle> first = running.runtime.openWindow(duplicate);
            CompletionStage<WindowHandle> second = running.runtime.openWindow(duplicate);

            assertThat(first.toCompletableFuture().get(5, TimeUnit.SECONDS).id())
                    .isEqualTo(new WindowId("dynamic"));
            assertThatThrownBy(() -> second.toCompletableFuture().join())
                    .isInstanceOfSatisfying(CompletionException.class,
                            e -> assertThat(e.getCause())
                                    .isInstanceOfSatisfying(JDeskException.class,
                                            jde -> assertThat(jde.code())
                                                    .isEqualTo(ErrorCode.ILLEGAL_STATE)));
            assertThat(running.provider.app.windows.stream()
                    .filter(window -> window.id().equals(new WindowId("dynamic"))))
                    .hasSize(1);
        }
    }

    @Test
    void rendererFailureInvalidatesSessionAndRenavigatesEntry() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            FakeWebView webView = running.provider.app.windows.getFirst().webView;
            int before = webView.navigations.size();
            webView.simulateProcessFailure();
            assertThat(webView.navigations).hasSize(before + 1).last().isEqualTo(URI.create(ENTRY));
        }
    }

    @Test
    void windowHandleClearsSelectedDataForItsNativeSession() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            WindowHandle handle = running.runtime.window(new WindowId("main")).orElseThrow();
            Set<WebViewDataType> selected = Set.of(
                    WebViewDataType.COOKIES, WebViewDataType.LOCAL_STORAGE);

            handle.clearWebViewData(selected).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertThat(running.provider.app.windows.getFirst().webView.clearedData)
                    .isEqualTo(selected);
        }
    }

    @Test void externalBrowserPolicyAllowsHttpAndRejectsFileCredentialsAndCustomSchemes()
            throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            URI allowed = URI.create("https://example.com/path?q=1");
            running.runtime.openExternal(allowed).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(running.provider.app.externalUris).containsExactly(allowed);
            for (URI denied : List.of(URI.create("file:///etc/passwd"),
                    URI.create("https://user:secret@example.com/"), URI.create("custom://host/x"))) {
                assertThatThrownBy(() -> running.runtime.openExternal(denied)
                        .toCompletableFuture().join()).hasCauseInstanceOf(JDeskException.class);
            }
            assertThat(running.provider.app.externalUris).containsExactly(allowed);
        }
    }

    @Test void managedPolicyDisablesExternalBrowserAndDeveloperTools(@TempDir Path temp)
            throws Exception {
        Path policy = temp.resolve("managed-policy.json");
        Files.writeString(policy, """
                {
                  "version": 1,
                  "externalBrowserAllowed": false,
                  "devToolsAllowed": false
                }
                """);
        System.setProperty("jdesk.policy.file", policy.toString());
        RunningRuntime running;
        try {
            running = new RunningRuntime(List.of(RunningRuntime.window("main")),
                    Optional.of("http://127.0.0.1:5173"), true);
        } finally {
            System.clearProperty("jdesk.policy.file");
        }
        try (running) {
            running.awaitReady();
            assertThat(running.provider.config.devMode()).isTrue();
            assertThat(running.provider.app.windows.getFirst().config.devToolsEnabled()).isFalse();
            assertThatThrownBy(() -> running.runtime.openExternal(
                    URI.create("https://example.com")).toCompletableFuture().join())
                    .hasCauseInstanceOf(JDeskException.class)
                    .hasMessageContaining("managed policy");
            assertThat(running.provider.app.externalUris).isEmpty();
        }
    }

    @Test void messageDialogIsMarshalledAndOversizeInputIsRejected() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();
            var result = running.runtime.showMessageDialog(
                    dev.jdesk.api.MessageDialog.ok("Title", "Body"))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result.buttonIndex()).isZero();
            assertThat(result.buttonLabel()).isEqualTo("OK");
            var oversized = new dev.jdesk.api.MessageDialog("Title", "x".repeat(16_385),
                    dev.jdesk.api.MessageDialog.Kind.INFO, List.of("OK"));
            assertThatThrownBy(() -> running.runtime.showMessageDialog(oversized)
                    .toCompletableFuture().join()).hasCauseInstanceOf(JDeskException.class);
        }
    }
}
