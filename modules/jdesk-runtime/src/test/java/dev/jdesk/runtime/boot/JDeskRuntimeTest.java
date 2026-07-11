package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ApplicationSpec;
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
import dev.jdesk.api.WindowId;
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
import dev.jdesk.webview.spi.WindowBounds;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

        @Override
        public void setTitle(String title) {
        }

        @Override
        public void setBounds(WindowBounds bounds) {
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

        @Override
        public void onStarting() {
            calls.add("starting");
        }

        @Override
        public void onReady() {
            calls.add("ready");
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
            ApplicationSpec spec = new ApplicationSpec(
                    "dev.jdesk.test",
                    CommandRegistry.of(),
                    CapabilitySet.empty(),
                    windowConfigs,
                    List.of(listener),
                    Optional.empty());
            runtime = new JDeskRuntime(spec, provider, RuntimeOptions.production(new MapAssetSource()));
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
        public void close() throws InterruptedException {
            if (thread.isAlive()) {
                if (provider.app != null) {
                    provider.app.requestStop();
                }
                thread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void runDrivesFullLifecycleAndWiresTheWindow() throws Exception {
        try (RunningRuntime running = new RunningRuntime(List.of(RunningRuntime.window("main")))) {
            running.awaitReady();

            // Ready was reached before the event loop was entered.
            assertThat(running.runtime.lifecycle().state()).isEqualTo(LifecycleState.READY);
            assertThat(running.listener.calls).startsWith("starting", "ready");

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
}
