package dev.jdesk.platform.linux;

import dev.jdesk.api.Subscription;
import dev.jdesk.api.WebViewCookie;
import dev.jdesk.api.WebViewCookieKey;
import dev.jdesk.api.WebViewDataType;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.webview.spi.InitScripts;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.NavigationListener;
import dev.jdesk.webview.spi.NavigationRequest;
import dev.jdesk.webview.spi.PlatformWebView;
import dev.jdesk.webview.spi.WebViewDiagnostics;
import dev.jdesk.webview.spi.WebViewProcessFailure;
import dev.jdesk.webview.spi.WebViewSnapshot;
import java.io.ByteArrayOutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * One WebKitWebView for one window. All calls are GTK-main-thread only; the runtime
 * marshals through {@link dev.jdesk.api.UiDispatcher}. {@code jdesk://app} requests are
 * served by the application-level URI scheme handler (registered on the default
 * WebKitWebContext before any view exists) — no sockets anywhere. Only public,
 * documented WebKitGTK 4.1 APIs are used.
 */
final class LinuxWebView implements PlatformWebView {
    private static final Logger LOG = System.getLogger(LinuxWebView.class.getName());

    /**
     * Uniform bridge contract shared by all adapters: window.__jdesk.post(string) sends;
     * incoming strings arrive as 'jdesk-message' CustomEvents on document. Envelopes
     * posted by the host before this document-start script ran (a commit-time race) are
     * parked in window.__jdeskPending by {@link #postJson} and drained here. The nonce
     * control envelope is captured here so it is never lost even when it arrives before
     * page scripts attach their listeners.
     */
    static final String INIT_SCRIPT = """
            (function () {
              if (window.__jdesk) return;
              window.__jdesk = {
                nonce: null,
                post: function (s) { window.webkit.messageHandlers.jdesk.postMessage(String(s)); },
                _deliver: function (data) {
                  try {
                    var m = JSON.parse(data);
                    if (m && m.kind === 'nonce' && typeof m.nonce === 'string') {
                      window.__jdesk.nonce = m.nonce;
                    }
                  } catch (err) { }
                  document.dispatchEvent(new CustomEvent('jdesk-message', { detail: data }));
                }
              };
              var pending = window.__jdeskPending;
              delete window.__jdeskPending;
              if (pending && pending.length) {
                for (var i = 0; i < pending.length; i++) {
                  try { window.__jdesk._deliver(pending[i]); } catch (err) { }
                }
              }
            })();
            """;

    // void (*)(WebKitUserContentManager*, WebKitJavascriptResult*, gpointer)
    private static final FunctionDescriptor SCRIPT_MESSAGE_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    // gboolean (*)(WebKitWebView*, WebKitPolicyDecision*, WebKitPolicyDecisionType, gpointer)
    private static final FunctionDescriptor DECIDE_POLICY_DESC =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    // void (*)(WebKitWebView*, WebKitLoadEvent, gpointer)
    private static final FunctionDescriptor LOAD_CHANGED_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    // void (*)(WebKitWebView*, WebKitWebProcessTerminationReason, gpointer)
    private static final FunctionDescriptor PROCESS_TERMINATED_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    // void (*GAsyncReadyCallback)(GObject*, GAsyncResult*, gpointer)
    private static final FunctionDescriptor ASYNC_READY_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    // cairo_status_t (*cairo_write_func_t)(void* closure, const unsigned char*, unsigned int)
    private static final FunctionDescriptor CAIRO_WRITE_DESC =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);

    /** One-shot completion for a GAsyncReadyCallback keyed by user_data token. */
    private interface AsyncHandler {
        void completed(MemorySegment source, MemorySegment result);
    }

    /** WebKitWebView / WebKitUserContentManager address -> web view peer. */
    private static final Map<Long, LinuxWebView> PEERS = new ConcurrentHashMap<>();
    private static final Map<Long, AsyncHandler> ASYNC = new ConcurrentHashMap<>();
    private static final Map<Long, ByteArrayOutputStream> PNG_SINKS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong(1);

    private static final MemorySegment SCRIPT_MESSAGE_STUB;
    private static final MemorySegment DECIDE_POLICY_STUB;
    private static final MemorySegment LOAD_CHANGED_STUB;
    private static final MemorySegment PROCESS_TERMINATED_STUB;
    private static final MemorySegment ASYNC_READY_STUB;
    private static final MemorySegment CAIRO_WRITE_STUB;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            SCRIPT_MESSAGE_STUB = Gtk.upcall(lookup.findStatic(LinuxWebView.class,
                            "onScriptMessage", MethodType.methodType(void.class,
                                    MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class)),
                    SCRIPT_MESSAGE_DESC);
            DECIDE_POLICY_STUB = Gtk.upcall(lookup.findStatic(LinuxWebView.class,
                            "onDecidePolicy", MethodType.methodType(int.class,
                                    MemorySegment.class, MemorySegment.class, int.class,
                                    MemorySegment.class)),
                    DECIDE_POLICY_DESC);
            LOAD_CHANGED_STUB = Gtk.upcall(lookup.findStatic(LinuxWebView.class,
                            "onLoadChanged", MethodType.methodType(void.class,
                                    MemorySegment.class, int.class, MemorySegment.class)),
                    LOAD_CHANGED_DESC);
            PROCESS_TERMINATED_STUB = Gtk.upcall(lookup.findStatic(LinuxWebView.class,
                            "onProcessTerminated", MethodType.methodType(void.class,
                                    MemorySegment.class, int.class, MemorySegment.class)),
                    PROCESS_TERMINATED_DESC);
            ASYNC_READY_STUB = Gtk.upcall(lookup.findStatic(LinuxWebView.class,
                            "onAsyncReady", MethodType.methodType(void.class,
                                    MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class)),
                    ASYNC_READY_DESC);
            CAIRO_WRITE_STUB = Gtk.upcall(lookup.findStatic(LinuxWebView.class,
                            "onCairoWrite", MethodType.methodType(int.class,
                                    MemorySegment.class, MemorySegment.class, int.class)),
                    CAIRO_WRITE_DESC);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private final NativeCallbackRegistry registry;
    private final MemorySegment webView;              // + one owned ref (g_object_ref_sink)
    private final MemorySegment userContentManager;   // + one owned ref (g_object_ref)
    private final MemorySegment webContext;           // application-owned
    private final boolean devToolsEnabled;
    private final List<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<NavigationListener> navigationListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<URI>> committedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WebViewProcessFailure>> failureListeners =
            new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    LinuxWebView(LinuxPlatformApplication app, LinuxWindow window, NativeWindowConfig config) {
        this.registry = window.callbackRegistry();
        final MemorySegment view;
        final MemorySegment manager;
        final MemorySegment context;
        final boolean actualDevToolsEnabled;
        try (Arena confined = Arena.ofConfined()) {
            context = app.webContext(config.webViewSession());
            MemorySegment created = (MemorySegment) Gtk.WEBKIT_WEB_VIEW_NEW_WITH_CONTEXT
                    .invokeExact(context);
            if (created.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("webkit_web_view_new failed");
            }
            // webkit_web_view_new returns a floating reference; sink it so this class
            // owns exactly one reference regardless of container add/remove.
            view = Gtk.gObjectRefSink(created);
            MemorySegment sharedManager = (MemorySegment)
                    Gtk.WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER.invokeExact(view);
            manager = Gtk.gObjectRef(sharedManager); // transfer none: take our own reference

            int registered = (int) Gtk
                    .WEBKIT_USER_CONTENT_MANAGER_REGISTER_SCRIPT_MESSAGE_HANDLER
                    .invokeExact(manager, confined.allocateFrom("jdesk"));
            if (registered == 0) {
                throw new IllegalStateException("script message handler registration failed");
            }
            String[] userScripts = config.consoleCapture()
                    ? new String[] {INIT_SCRIPT, InitScripts.CONSOLE_CAPTURE}
                    : new String[] {INIT_SCRIPT};
            for (String source : userScripts) {
                MemorySegment userScript = (MemorySegment) Gtk.WEBKIT_USER_SCRIPT_NEW.invokeExact(
                        confined.allocateFrom(source),
                        Gtk.WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
                        Gtk.WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START,
                        MemorySegment.NULL, MemorySegment.NULL);
                Gtk.WEBKIT_USER_CONTENT_MANAGER_ADD_SCRIPT.invokeExact(manager, userScript);
                Gtk.WEBKIT_USER_SCRIPT_UNREF.invokeExact(userScript);
            }

            if (config.devToolsEnabled()) {
                MemorySegment settings = (MemorySegment)
                        Gtk.WEBKIT_WEB_VIEW_GET_SETTINGS.invokeExact(view);
                Gtk.WEBKIT_SETTINGS_SET_ENABLE_DEVELOPER_EXTRAS.invokeExact(settings, 1);
            }
            if (config.webViewSession().userAgent().isPresent()) {
                MemorySegment settings = (MemorySegment)
                        Gtk.WEBKIT_WEB_VIEW_GET_SETTINGS.invokeExact(view);
                Gtk.WEBKIT_SETTINGS_SET_USER_AGENT.invokeExact(settings,
                        confined.allocateFrom(config.webViewSession().userAgent().orElseThrow()));
            }
            MemorySegment settings = (MemorySegment)
                    Gtk.WEBKIT_WEB_VIEW_GET_SETTINGS.invokeExact(view);
            actualDevToolsEnabled = (int) Gtk.WEBKIT_SETTINGS_GET_ENABLE_DEVELOPER_EXTRAS
                    .invokeExact(settings) != 0;
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
        this.webView = view;
        this.userContentManager = manager;
        this.webContext = context;
        this.devToolsEnabled = actualDevToolsEnabled;

        PEERS.put(view.address(), this);
        PEERS.put(manager.address(), this);
        registry.register(new NativeCallbackRegistry.Registration(
                "webview-peer", this, MethodHandles.constant(Object.class, null),
                SCRIPT_MESSAGE_STUB, null, () -> {
                    PEERS.remove(view.address());
                    PEERS.remove(manager.address());
                }));

        long scriptMessageId = Gtk.signalConnect(manager,
                "script-message-received::jdesk", SCRIPT_MESSAGE_STUB);
        registry.register(new NativeCallbackRegistry.Registration(
                "script-message-received", this, MethodHandles.constant(Object.class, null),
                SCRIPT_MESSAGE_STUB, scriptMessageId,
                () -> Gtk.signalDisconnect(manager, scriptMessageId)));
        long decidePolicyId = Gtk.signalConnect(view, "decide-policy", DECIDE_POLICY_STUB);
        registry.register(new NativeCallbackRegistry.Registration(
                "decide-policy", this, MethodHandles.constant(Object.class, null),
                DECIDE_POLICY_STUB, decidePolicyId,
                () -> Gtk.signalDisconnect(view, decidePolicyId)));
        long loadChangedId = Gtk.signalConnect(view, "load-changed", LOAD_CHANGED_STUB);
        registry.register(new NativeCallbackRegistry.Registration(
                "load-changed", this, MethodHandles.constant(Object.class, null),
                LOAD_CHANGED_STUB, loadChangedId,
                () -> Gtk.signalDisconnect(view, loadChangedId)));
        long terminatedId = Gtk.signalConnect(view, "web-process-terminated",
                PROCESS_TERMINATED_STUB);
        registry.register(new NativeCallbackRegistry.Registration(
                "web-process-terminated", this, MethodHandles.constant(Object.class, null),
                PROCESS_TERMINATED_STUB, terminatedId,
                () -> Gtk.signalDisconnect(view, terminatedId)));
    }

    MemorySegment widget() {
        return webView;
    }

    // ---- signal trampolines (GTK main thread; copy data, return fast, never block) ----

    @SuppressWarnings("unused") // GCallback upcall
    static void onScriptMessage(MemorySegment manager, MemorySegment jsResult,
            MemorySegment userData) {
        LinuxWebView peer = PEERS.get(manager.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return;
        }
        try {
            MemorySegment jscValue = (MemorySegment)
                    Gtk.WEBKIT_JAVASCRIPT_RESULT_GET_JS_VALUE.invokeExact(jsResult);
            if (jscValue.equals(MemorySegment.NULL)) {
                return;
            }
            String text = Gtk.takeString((MemorySegment)
                    Gtk.JSC_VALUE_TO_STRING.invokeExact(jscValue));
            if (text == null) {
                return;
            }
            LOG.log(Level.INFO, "bridge<- {0} chars", text.length());
            for (Consumer<String> listener : peer.messageListeners) {
                listener.accept(text);
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Script message handling failed", t);
        } finally {
            peer.registry.gate().exit();
        }
    }

    @SuppressWarnings("unused") // GCallback upcall
    static int onDecidePolicy(MemorySegment view, MemorySegment decision, int decisionType,
            MemorySegment userData) {
        LinuxWebView peer = PEERS.get(view.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return 0; // default handling
        }
        try {
            return peer.decidePolicy(decision, decisionType);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Navigation policy decision failed; blocking", t);
            try {
                Gtk.WEBKIT_POLICY_DECISION_IGNORE.invokeExact(decision);
            } catch (Throwable ignoreFailure) {
                LOG.log(Level.ERROR, "policy ignore failed", ignoreFailure);
            }
            return 1;
        } finally {
            peer.registry.gate().exit();
        }
    }

    @SuppressWarnings("unused") // GCallback upcall
    static void onLoadChanged(MemorySegment view, int loadEvent, MemorySegment userData) {
        if (loadEvent != Gtk.WEBKIT_LOAD_COMMITTED) {
            return;
        }
        LinuxWebView peer = PEERS.get(view.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return;
        }
        try {
            URI uri = peer.currentUrl();
            LOG.log(Level.INFO, "load-committed url={0}", uri);
            for (Consumer<URI> listener : peer.committedListeners) {
                listener.accept(uri);
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "load-committed handling failed", t);
        } finally {
            peer.registry.gate().exit();
        }
    }

    @SuppressWarnings("unused") // GCallback upcall
    static void onProcessTerminated(MemorySegment view, int reason, MemorySegment userData) {
        LinuxWebView peer = PEERS.get(view.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return;
        }
        try {
            // WebKitWebProcessTerminationReason (webkit2/WebKitWebView.h):
            // 0 = crashed, 1 = exceeded memory limit, 2 = terminated by API.
            WebViewProcessFailure failure = new WebViewProcessFailure(
                    WebViewProcessFailure.Kind.RENDER_PROCESS_EXITED,
                    "web-process-terminated reason=" + reason);
            LOG.log(Level.ERROR, "WebKit process failure: {0}", failure);
            for (Consumer<WebViewProcessFailure> listener : peer.failureListeners) {
                listener.accept(failure);
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Process-failure handling failed", t);
        } finally {
            peer.registry.gate().exit();
        }
    }

    @SuppressWarnings("unused") // GAsyncReadyCallback upcall
    static void onAsyncReady(MemorySegment source, MemorySegment result,
            MemorySegment userData) {
        AsyncHandler handler = ASYNC.remove(userData.address());
        if (handler == null) {
            return;
        }
        try {
            handler.completed(source, result);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Async completion failed", t);
        }
    }

    @SuppressWarnings("unused") // cairo_write_func_t upcall
    static int onCairoWrite(MemorySegment closure, MemorySegment data, int length) {
        ByteArrayOutputStream sink = PNG_SINKS.get(closure.address());
        if (sink == null) {
            return Gtk.CAIRO_STATUS_WRITE_ERROR;
        }
        byte[] chunk = new byte[length];
        MemorySegment.copy(data.reinterpret(length), JAVA_BYTE, 0, chunk, 0, length);
        sink.write(chunk, 0, length);
        return Gtk.CAIRO_STATUS_SUCCESS;
    }

    private int decidePolicy(MemorySegment decision, int decisionType) throws Throwable {
        if (decisionType == Gtk.WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
            // New-window/popup target: denied by default (spec 12.2).
            LOG.log(Level.WARNING, "Blocked popup/new-window navigation request");
            Gtk.WEBKIT_POLICY_DECISION_IGNORE.invokeExact(decision);
            return 1;
        }
        if (decisionType != Gtk.WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) {
            return 0; // WEBKIT_POLICY_DECISION_TYPE_RESPONSE: default handling
        }
        MemorySegment action = (MemorySegment)
                Gtk.WEBKIT_NAVIGATION_POLICY_DECISION_GET_NAVIGATION_ACTION
                        .invokeExact(decision);
        MemorySegment uriRequest = (MemorySegment)
                Gtk.WEBKIT_NAVIGATION_ACTION_GET_REQUEST.invokeExact(action);
        String url = Gtk.javaString((MemorySegment)
                Gtk.WEBKIT_URI_REQUEST_GET_URI.invokeExact(uriRequest));
        // Best-effort flag (not a security boundary): link activation (0) or form
        // submission (1) — WebKitNavigationType, webkit2/WebKitNavigationAction.h.
        int navigationType = (int)
                Gtk.WEBKIT_NAVIGATION_ACTION_GET_NAVIGATION_TYPE.invokeExact(action);
        boolean userInitiated = navigationType == 0 || navigationType == 1;
        NavigationRequest request;
        try {
            // NAVIGATION_ACTION decisions are treated as main-frame policy checks; the
            // capability engine independently gates per-origin, and popups/new windows
            // are blocked above.
            request = new NavigationRequest(URI.create(url == null ? "" : url),
                    true, userInitiated);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Blocked navigation to unparseable target");
            Gtk.WEBKIT_POLICY_DECISION_IGNORE.invokeExact(decision);
            return 1;
        }
        for (NavigationListener listener : navigationListeners) {
            if (listener.onNavigate(request) == NavigationDecision.BLOCK) {
                LOG.log(Level.INFO, "decide-policy BLOCK uri={0}", url);
                Gtk.WEBKIT_POLICY_DECISION_IGNORE.invokeExact(decision);
                return 1;
            }
        }
        LOG.log(Level.INFO, "decide-policy ALLOW uri={0}", url);
        Gtk.WEBKIT_POLICY_DECISION_USE.invokeExact(decision);
        return 1;
    }

    private URI currentUrl() {
        try {
            String url = Gtk.javaString((MemorySegment)
                    Gtk.WEBKIT_WEB_VIEW_GET_URI.invokeExact(webView));
            return url == null || url.isEmpty() ? URI.create("about:blank") : URI.create(url);
        } catch (Throwable t) {
            return URI.create("about:blank");
        }
    }

    // ---- PlatformWebView ----

    @Override
    public void navigate(URI uri) {
        try (Arena confined = Arena.ofConfined()) {
            Gtk.WEBKIT_WEB_VIEW_LOAD_URI.invokeExact(webView,
                    confined.allocateFrom(uri.toString()));
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override
    public void postJson(String json) {
        if (closed) {
            return;
        }
        LOG.log(Level.INFO, "bridge-> {0} chars", json.length());
        String script = "(function(d){if(window.__jdesk&&window.__jdesk._deliver)"
                + "{window.__jdesk._deliver(d);}"
                + "else{(window.__jdeskPending=window.__jdeskPending||[]).push(d);}})("
                + jsStringLiteral(json) + ");";
        // NULL callback is public API; no per-message state is created.
        runJavaScript(script, MemorySegment.NULL, MemorySegment.NULL);
    }

    @Override
    public CompletionStage<String> evaluate(String script) {
        CompletableFuture<String> future = new CompletableFuture<>();
        long token = TOKENS.getAndIncrement();
        ASYNC.put(token, (source, result) -> {
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment errorSlot = confined.allocate(ADDRESS);
                errorSlot.set(ADDRESS, 0, MemorySegment.NULL);
                MemorySegment value = (MemorySegment)
                        Gtk.WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT_FINISH.invokeExact(
                                source, result, errorSlot);
                if (value.equals(MemorySegment.NULL)) {
                    future.completeExceptionally(new IllegalStateException(
                            "evaluate_javascript failed: " + Gtk.takeErrorMessage(errorSlot)));
                    return;
                }
                String text = Gtk.takeString((MemorySegment)
                        Gtk.JSC_VALUE_TO_STRING.invokeExact(value));
                Gtk.gObjectUnref(value); // finish returns a transfer-full JSCValue
                future.complete(text == null ? "null" : text);
            } catch (Throwable t) {
                future.completeExceptionally(Gtk.rethrow(t));
            }
        });
        try {
            runJavaScript(script, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
        } catch (RuntimeException e) {
            ASYNC.remove(token);
            future.completeExceptionally(e);
        }
        return future;
    }

    private void runJavaScript(String script, MemorySegment callback, MemorySegment userData) {
        try (Arena confined = Arena.ofConfined()) {
            // webkit_web_view_evaluate_javascript(view, script, -1 (NUL-terminated),
            // world=NULL (main world), source_uri=NULL, cancellable=NULL, cb, data)
            Gtk.WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT.invokeExact(webView,
                    confined.allocateFrom(script), -1L, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL, callback, userData);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override
    public Subscription onMessage(Consumer<String> listener) {
        messageListeners.add(listener);
        return () -> messageListeners.remove(listener);
    }

    @Override
    public Subscription onNavigation(NavigationListener listener) {
        navigationListeners.add(listener);
        return () -> navigationListeners.remove(listener);
    }

    @Override
    public Subscription onNavigationCommitted(Consumer<URI> listener) {
        committedListeners.add(listener);
        return () -> committedListeners.remove(listener);
    }

    /** Subscribes to engine process failures (spec section 13). */
    @Override
    public Subscription onProcessFailure(Consumer<WebViewProcessFailure> listener) {
        failureListeners.add(listener);
        return () -> failureListeners.remove(listener);
    }

    @Override
    public CompletionStage<WebViewSnapshot> snapshot() {
        CompletableFuture<WebViewSnapshot> future = new CompletableFuture<>();
        long token = TOKENS.getAndIncrement();
        ASYNC.put(token, (source, result) -> {
            MemorySegment surface = MemorySegment.NULL;
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment errorSlot = confined.allocate(ADDRESS);
                errorSlot.set(ADDRESS, 0, MemorySegment.NULL);
                surface = (MemorySegment) Gtk.WEBKIT_WEB_VIEW_GET_SNAPSHOT_FINISH
                        .invokeExact(source, result, errorSlot);
                if (surface.equals(MemorySegment.NULL)) {
                    future.completeExceptionally(new IllegalStateException(
                            "get_snapshot failed: " + Gtk.takeErrorMessage(errorSlot)));
                    return;
                }
                int width = (int) Gtk.CAIRO_IMAGE_SURFACE_GET_WIDTH.invokeExact(surface);
                int height = (int) Gtk.CAIRO_IMAGE_SURFACE_GET_HEIGHT.invokeExact(surface);
                ByteArrayOutputStream sink = new ByteArrayOutputStream(width * height);
                PNG_SINKS.put(token, sink);
                int status;
                try {
                    // Real engine capture: the surface comes from WebKit's own snapshot
                    // API; PNG encoding through cairo's public stream writer.
                    status = (int) Gtk.CAIRO_SURFACE_WRITE_TO_PNG_STREAM.invokeExact(
                            surface, CAIRO_WRITE_STUB, MemorySegment.ofAddress(token));
                } finally {
                    PNG_SINKS.remove(token);
                }
                if (status != Gtk.CAIRO_STATUS_SUCCESS) {
                    future.completeExceptionally(new IllegalStateException(
                            "cairo PNG encoding failed: status " + status));
                    return;
                }
                future.complete(new WebViewSnapshot(width, height, sink.toByteArray()));
            } catch (Throwable t) {
                future.completeExceptionally(Gtk.rethrow(t));
            } finally {
                if (!surface.equals(MemorySegment.NULL)) {
                    try {
                        Gtk.CAIRO_SURFACE_DESTROY.invokeExact(surface);
                    } catch (Throwable t) {
                        LOG.log(Level.ERROR, "cairo_surface_destroy failed", t);
                    }
                }
            }
        });
        try {
            Gtk.WEBKIT_WEB_VIEW_GET_SNAPSHOT.invokeExact(webView,
                    Gtk.WEBKIT_SNAPSHOT_REGION_VISIBLE, Gtk.WEBKIT_SNAPSHOT_OPTIONS_NONE,
                    MemorySegment.NULL, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
        } catch (Throwable t) {
            ASYNC.remove(token);
            future.completeExceptionally(Gtk.rethrow(t));
        }
        return future;
    }

    @Override
    public WebViewDiagnostics diagnostics() {
        String userAgent = null;
        try {
            MemorySegment settings = (MemorySegment)
                    Gtk.WEBKIT_WEB_VIEW_GET_SETTINGS.invokeExact(webView);
            userAgent = Gtk.javaString((MemorySegment)
                    Gtk.WEBKIT_SETTINGS_GET_USER_AGENT.invokeExact(settings));
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "user agent unavailable", t);
        }
        return new WebViewDiagnostics(
                Optional.of("WebKitGTK " + LinuxPlatformApplication.webKitVersion()),
                Optional.ofNullable(userAgent),
                Optional.empty());
    }
    @Override public boolean devToolsEnabled(){return devToolsEnabled;}

    @Override
    public CompletionStage<Void> clearData(Set<WebViewDataType> dataTypes) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        int types = 0;
        if (dataTypes.contains(WebViewDataType.COOKIES)) {
            types |= Gtk.WEBKIT_WEBSITE_DATA_COOKIES;
        }
        if (dataTypes.contains(WebViewDataType.CACHE)) {
            types |= Gtk.WEBKIT_WEBSITE_DATA_MEMORY_CACHE
                    | Gtk.WEBKIT_WEBSITE_DATA_DISK_CACHE;
        }
        if (dataTypes.contains(WebViewDataType.LOCAL_STORAGE)) {
            types |= Gtk.WEBKIT_WEBSITE_DATA_LOCAL_STORAGE;
        }
        long token = TOKENS.getAndIncrement();
        ASYNC.put(token, (source, result) -> {
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment errorSlot = confined.allocate(ADDRESS);
                errorSlot.set(ADDRESS, 0, MemorySegment.NULL);
                int cleared = (int) Gtk.WEBKIT_WEBSITE_DATA_MANAGER_CLEAR_FINISH
                        .invokeExact(source, result, errorSlot);
                if (cleared == 0) {
                    future.completeExceptionally(new IllegalStateException(
                            "website data clear failed: " + Gtk.takeErrorMessage(errorSlot)));
                } else {
                    future.complete(null);
                }
            } catch (Throwable t) {
                future.completeExceptionally(Gtk.rethrow(t));
            }
        });
        try {
            MemorySegment dataManager = (MemorySegment)
                    Gtk.WEBKIT_WEB_CONTEXT_GET_WEBSITE_DATA_MANAGER.invokeExact(webContext);
            Gtk.WEBKIT_WEBSITE_DATA_MANAGER_CLEAR.invokeExact(dataManager, types, 0L,
                    MemorySegment.NULL, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
        } catch (Throwable t) {
            ASYNC.remove(token);
            future.completeExceptionally(Gtk.rethrow(t));
        }
        return future;
    }

    @Override
    public CompletionStage<List<WebViewCookie>> cookies() {
        CompletableFuture<List<WebViewCookie>> future = new CompletableFuture<>();
        long token = TOKENS.getAndIncrement();
        ASYNC.put(token, (source, result) -> {
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment errorSlot = confined.allocate(ADDRESS);
                errorSlot.set(ADDRESS, 0, MemorySegment.NULL);
                MemorySegment list = (MemorySegment)
                        Gtk.WEBKIT_COOKIE_MANAGER_GET_ALL_COOKIES_FINISH.invokeExact(
                                source, result, errorSlot);
                if (list.equals(MemorySegment.NULL)) {
                    MemorySegment error = errorSlot.get(ADDRESS, 0);
                    if (!error.equals(MemorySegment.NULL)) {
                        future.completeExceptionally(new IllegalStateException(
                                "get all cookies failed: " + Gtk.takeErrorMessage(errorSlot)));
                    } else {
                        future.complete(List.of());
                    }
                    return;
                }
                try {
                    List<WebViewCookie> cookies = new ArrayList<>();
                    for (MemorySegment node = list; !node.equals(MemorySegment.NULL);
                            node = node.reinterpret(16).get(ADDRESS, 8)) {
                        MemorySegment cookie = node.reinterpret(16).get(ADDRESS, 0);
                        cookies.add(javaCookie(cookie));
                    }
                    future.complete(List.copyOf(cookies));
                } finally {
                    freeCookieList(list);
                }
            } catch (Throwable t) {
                future.completeExceptionally(Gtk.rethrow(t));
            }
        });
        try {
            Gtk.WEBKIT_COOKIE_MANAGER_GET_ALL_COOKIES.invokeExact(cookieManager(),
                    MemorySegment.NULL, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
        } catch (Throwable t) {
            ASYNC.remove(token);
            future.completeExceptionally(Gtk.rethrow(t));
        }
        return future;
    }

    @Override
    public CompletionStage<Void> setCookie(WebViewCookie cookie) {
        MemorySegment nativeCookie;
        try {
            nativeCookie = nativeCookie(cookie);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return mutateCookie(nativeCookie, true);
    }

    @Override
    public CompletionStage<Void> deleteCookie(WebViewCookieKey key) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long token = TOKENS.getAndIncrement();
        ASYNC.put(token, (source, result) -> {
            MemorySegment copy = MemorySegment.NULL;
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment errorSlot = confined.allocate(ADDRESS);
                errorSlot.set(ADDRESS, 0, MemorySegment.NULL);
                MemorySegment list = (MemorySegment)
                        Gtk.WEBKIT_COOKIE_MANAGER_GET_ALL_COOKIES_FINISH.invokeExact(
                                source, result, errorSlot);
                if (list.equals(MemorySegment.NULL)) {
                    MemorySegment error = errorSlot.get(ADDRESS, 0);
                    if (!error.equals(MemorySegment.NULL)) {
                        future.completeExceptionally(new IllegalStateException(
                                "locate cookie for delete failed: "
                                        + Gtk.takeErrorMessage(errorSlot)));
                    } else {
                        future.complete(null);
                    }
                    return;
                }
                try {
                    for (MemorySegment node = list; !node.equals(MemorySegment.NULL);
                            node = node.reinterpret(16).get(ADDRESS, 8)) {
                        MemorySegment candidate = node.reinterpret(16).get(ADDRESS, 0);
                        if (matchesCookieKey(candidate, key)) {
                            copy = (MemorySegment) Gtk.SOUP_COOKIE_COPY.invokeExact(candidate);
                            break;
                        }
                    }
                } finally {
                    freeCookieList(list);
                }
                if (copy.equals(MemorySegment.NULL)) {
                    future.complete(null); // Missing keys are intentionally idempotent.
                } else {
                    MemorySegment cookieToDelete = copy;
                    copy = MemorySegment.NULL; // mutateCookie now owns and frees it.
                    mutateCookie(cookieToDelete, false).whenComplete((ignored, error) -> {
                        if (error == null) {
                            future.complete(null);
                        } else {
                            future.completeExceptionally(error);
                        }
                    });
                }
            } catch (Throwable t) {
                if (!copy.equals(MemorySegment.NULL)) {
                    freeCookie(copy);
                }
                future.completeExceptionally(Gtk.rethrow(t));
            }
        });
        try {
            Gtk.WEBKIT_COOKIE_MANAGER_GET_ALL_COOKIES.invokeExact(cookieManager(),
                    MemorySegment.NULL, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
        } catch (Throwable t) {
            ASYNC.remove(token);
            future.completeExceptionally(Gtk.rethrow(t));
        }
        return future;
    }

    private CompletionStage<Void> mutateCookie(MemorySegment cookie, boolean add) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long token = TOKENS.getAndIncrement();
        ASYNC.put(token, (source, result) -> {
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment errorSlot = confined.allocate(ADDRESS);
                errorSlot.set(ADDRESS, 0, MemorySegment.NULL);
                int changed = add
                        ? (int) Gtk.WEBKIT_COOKIE_MANAGER_ADD_COOKIE_FINISH.invokeExact(
                                source, result, errorSlot)
                        : (int) Gtk.WEBKIT_COOKIE_MANAGER_DELETE_COOKIE_FINISH.invokeExact(
                                source, result, errorSlot);
                if (changed == 0 && !errorSlot.get(ADDRESS, 0).equals(MemorySegment.NULL)) {
                    future.completeExceptionally(new IllegalStateException(
                            (add ? "add" : "delete") + " cookie failed: "
                                    + Gtk.takeErrorMessage(errorSlot)));
                } else {
                    // Deleting a missing key is intentionally idempotent.
                    future.complete(null);
                }
            } catch (Throwable t) {
                future.completeExceptionally(Gtk.rethrow(t));
            } finally {
                freeCookie(cookie);
            }
        });
        try {
            if (add) {
                Gtk.WEBKIT_COOKIE_MANAGER_ADD_COOKIE.invokeExact(cookieManager(), cookie,
                        MemorySegment.NULL, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
            } else {
                Gtk.WEBKIT_COOKIE_MANAGER_DELETE_COOKIE.invokeExact(cookieManager(), cookie,
                        MemorySegment.NULL, ASYNC_READY_STUB, MemorySegment.ofAddress(token));
            }
        } catch (Throwable t) {
            ASYNC.remove(token);
            freeCookie(cookie);
            future.completeExceptionally(Gtk.rethrow(t));
        }
        return future;
    }

    private MemorySegment cookieManager() throws Throwable {
        MemorySegment dataManager = (MemorySegment)
                Gtk.WEBKIT_WEB_CONTEXT_GET_WEBSITE_DATA_MANAGER.invokeExact(webContext);
        return (MemorySegment)
                Gtk.WEBKIT_WEBSITE_DATA_MANAGER_GET_COOKIE_MANAGER.invokeExact(dataManager);
    }

    private WebViewCookie javaCookie(MemorySegment cookie) throws Throwable {
        MemorySegment expires = (MemorySegment) Gtk.SOUP_COOKIE_GET_EXPIRES.invokeExact(cookie);
        Optional<Instant> expiresAt = expires.equals(MemorySegment.NULL)
                ? Optional.empty()
                : Optional.of(Instant.ofEpochSecond(
                        (long) Gtk.G_DATE_TIME_TO_UNIX.invokeExact(expires)));
        return new WebViewCookie(
                Gtk.javaString((MemorySegment) Gtk.SOUP_COOKIE_GET_NAME.invokeExact(cookie)),
                Gtk.javaString((MemorySegment) Gtk.SOUP_COOKIE_GET_VALUE.invokeExact(cookie)),
                Gtk.javaString((MemorySegment) Gtk.SOUP_COOKIE_GET_DOMAIN.invokeExact(cookie)),
                Gtk.javaString((MemorySegment) Gtk.SOUP_COOKIE_GET_PATH.invokeExact(cookie)),
                expiresAt,
                (int) Gtk.SOUP_COOKIE_GET_SECURE.invokeExact(cookie) != 0,
                (int) Gtk.SOUP_COOKIE_GET_HTTP_ONLY.invokeExact(cookie) != 0);
    }

    private boolean matchesCookieKey(MemorySegment cookie, WebViewCookieKey key) throws Throwable {
        return key.name().equals(Gtk.javaString(
                (MemorySegment) Gtk.SOUP_COOKIE_GET_NAME.invokeExact(cookie)))
                && key.domain().equals(Gtk.javaString(
                        (MemorySegment) Gtk.SOUP_COOKIE_GET_DOMAIN.invokeExact(cookie)))
                && key.path().equals(Gtk.javaString(
                        (MemorySegment) Gtk.SOUP_COOKIE_GET_PATH.invokeExact(cookie)));
    }

    private MemorySegment nativeCookie(WebViewCookie cookie) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment nativeCookie = (MemorySegment) Gtk.SOUP_COOKIE_NEW.invokeExact(
                    confined.allocateFrom(cookie.name()), confined.allocateFrom(cookie.value()),
                    confined.allocateFrom(cookie.domain()), confined.allocateFrom(cookie.path()),
                    -1);
            if (nativeCookie.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("libsoup rejected cookie " + cookie.key());
            }
            try {
                if (cookie.expiresAt().isPresent()) {
                    MemorySegment date = (MemorySegment)
                            Gtk.G_DATE_TIME_NEW_FROM_UNIX_UTC.invokeExact(
                                    cookie.expiresAt().orElseThrow().getEpochSecond());
                    if (date.equals(MemorySegment.NULL)) {
                        throw new IllegalArgumentException(
                                "Cookie expiry is outside the GLib supported range");
                    }
                    try {
                        Gtk.SOUP_COOKIE_SET_EXPIRES.invokeExact(nativeCookie, date);
                    } finally {
                        Gtk.G_DATE_TIME_UNREF.invokeExact(date);
                    }
                }
                Gtk.SOUP_COOKIE_SET_SECURE.invokeExact(nativeCookie, cookie.secure() ? 1 : 0);
                Gtk.SOUP_COOKIE_SET_HTTP_ONLY.invokeExact(
                        nativeCookie, cookie.httpOnly() ? 1 : 0);
                return nativeCookie;
            } catch (Throwable t) {
                freeCookie(nativeCookie);
                throw t;
            }
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    private static void freeCookieList(MemorySegment list) throws Throwable {
        for (MemorySegment node = list; !node.equals(MemorySegment.NULL);
                node = node.reinterpret(16).get(ADDRESS, 8)) {
            freeCookie(node.reinterpret(16).get(ADDRESS, 0));
        }
        Gtk.G_LIST_FREE.invokeExact(list);
    }

    private static void freeCookie(MemorySegment cookie) {
        try {
            Gtk.SOUP_COOKIE_FREE.invokeExact(cookie);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    /** Called from the window's destroy path; detaches and releases the pipeline. */
    void destroyFromWindow() {
        if (closed) {
            return;
        }
        closed = true;
        try (Arena confined = Arena.ofConfined()) {
            Gtk.WEBKIT_WEB_VIEW_STOP_LOADING.invokeExact(webView);
            Gtk.WEBKIT_USER_CONTENT_MANAGER_UNREGISTER_SCRIPT_MESSAGE_HANDLER.invokeExact(
                    userContentManager, confined.allocateFrom("jdesk"));
            Gtk.WEBKIT_USER_CONTENT_MANAGER_REMOVE_ALL_SCRIPTS.invokeExact(userContentManager);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "WebKitWebView teardown failed", Gtk.rethrow(t));
        }
        // Signal disconnects and peer-map removal run next in the window's registry
        // close (reverse registration order); afterwards drop our owned references.
        // The container (window) still holds its own reference until GTK destroys the
        // child widget, so no live callback can observe freed memory.
        Gtk.gObjectUnref(userContentManager);
        Gtk.gObjectUnref(webView);
    }

    @Override
    public void close() {
        // The window owns the teardown ordering; closing the WebView alone closes it too.
        destroyFromWindow();
    }

    /** Escapes a string as a double-quoted JavaScript string literal. */
    static String jsStringLiteral(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\u2028' -> builder.append("\\u2028");
                case '\u2029' -> builder.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
