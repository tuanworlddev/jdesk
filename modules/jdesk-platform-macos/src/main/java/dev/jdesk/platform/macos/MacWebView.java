package dev.jdesk.platform.macos;

import dev.jdesk.api.Subscription;
import dev.jdesk.api.WebViewSessionConfig;
import dev.jdesk.api.WebViewCookie;
import dev.jdesk.api.WebViewCookieKey;
import dev.jdesk.api.WebViewDataType;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.webview.spi.InitScripts;
import dev.jdesk.webview.spi.AssetRequest;
import dev.jdesk.webview.spi.AssetResponse;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.NavigationListener;
import dev.jdesk.webview.spi.NavigationRequest;
import dev.jdesk.webview.spi.PlatformWebView;
import dev.jdesk.webview.spi.WebViewDiagnostics;
import dev.jdesk.webview.spi.WebViewProcessFailure;
import dev.jdesk.webview.spi.WebViewSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * One WKWebView for one window. All calls are main-thread only; the runtime marshals
 * through {@link dev.jdesk.api.UiDispatcher}. {@code jdesk://app} requests are served
 * through a {@code WKURLSchemeHandler} backed by the runtime
 * {@link dev.jdesk.webview.spi.AssetHandler} — no sockets anywhere. Only public,
 * documented WebKit/AppKit APIs are used.
 */
final class MacWebView implements PlatformWebView {
    private static final Logger LOG = System.getLogger(MacWebView.class.getName());

    /**
     * Uniform bridge contract shared by all adapters: window.__jdesk.post(string) sends;
     * incoming strings arrive as 'jdesk-message' CustomEvents on document. Envelopes
     * posted by the host before this document-start script ran (a commit-time race) are
     * parked in window.__jdeskPending by {@link #postJson} and drained here.
     */
    static final String INIT_SCRIPT = """
            (function () {
              if (window.__jdesk) return;
              window.__jdesk = {
                nonce: null,
                post: function (s) { window.webkit.messageHandlers.jdesk.postMessage(String(s)); },
                _deliver: function (data) {
                  // The nonce control envelope can arrive before page scripts attach
                  // their listeners; capture it here so it is never lost.
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

    private static final long WK_USER_SCRIPT_AT_DOCUMENT_START = 0; // WKUserScript.h
    private static final long WK_NAVIGATION_POLICY_CANCEL = 0;      // WKNavigationDelegate.h
    private static final long WK_NAVIGATION_POLICY_ALLOW = 1;
    private static final long NS_BITMAP_FILE_TYPE_PNG = 4;          // NSBitmapImageRep.h
    /** Async body streaming: sized to keep main-thread hops rare for large assets. */
    private static final int ASYNC_CHUNK_SIZE = 256 * 1024;

    // - initWithFrame:configuration:
    private static final FunctionDescriptor INIT_WEBVIEW_DESC = FunctionDescriptor.of(ADDRESS,
            ADDRESS, ADDRESS, ObjC.NSRECT, ADDRESS);
    // - initWithSource:injectionTime:forMainFrameOnly:
    private static final FunctionDescriptor INIT_USER_SCRIPT_DESC = FunctionDescriptor.of(ADDRESS,
            ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_BYTE);
    // - initWithURL:statusCode:HTTPVersion:headerFields:
    private static final FunctionDescriptor INIT_HTTP_RESPONSE_DESC = FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
    // + errorWithDomain:code:userInfo:
    private static final FunctionDescriptor ERROR_WITH_DOMAIN_DESC = FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);
    // + dataWithBytes:length:
    private static final FunctionDescriptor DATA_WITH_BYTES_DESC = FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    // + dictionaryWithCapacity:
    private static final FunctionDescriptor DICT_WITH_CAPACITY_DESC = FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    // - CGImageForProposedRect:context:hints:
    private static final FunctionDescriptor CGIMAGE_DESC = FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    // - representationUsingType:properties:
    private static final FunctionDescriptor REPRESENTATION_DESC = FunctionDescriptor.of(
            ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);

    /** Delegate/handler instance address -> web view peer. */
    private static final Map<Long, MacWebView> PEERS = new ConcurrentHashMap<>();
    private static final Object CLASS_LOCK = new Object();
    private static MemorySegment scriptMessageHandlerClass;
    private static MemorySegment schemeHandlerClass;
    private static MemorySegment navigationDelegateClass;

    private final MacPlatformApplication app;
    private final MacWindow window;
    private final NativeCallbackRegistry registry;
    private final MemorySegment webView;               // owned (+1 from alloc)
    private final MemorySegment userContentController; // retained (+1); shared with the live view
    private final MemorySegment scriptMessageHandler;  // owned (+1); also retained by the controller
    private final MemorySegment schemeHandler;         // owned (+1); also retained by the configuration
    private final MemorySegment navigationDelegate;    // owned (+1); navigationDelegate is weak
    private final MemorySegment websiteDataStore;      // application-owned
    private final boolean devToolsEnabled;
    private final List<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<NavigationListener> navigationListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<URI>> committedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WebViewProcessFailure>> failureListeners =
            new CopyOnWriteArrayList<>();
    /** In-flight scheme tasks by task address; the flag is set by stopURLSchemeTask. */
    private final Map<Long, AtomicBoolean> inflightTasks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    MacWebView(MacPlatformApplication app, MacWindow window, NativeWindowConfig config) {
        this.app = app;
        this.window = window;
        this.registry = window.callbackRegistry();
        ensureClasses();

        MemorySegment configuration = ObjC.send(
                ObjC.send(ObjC.cls("WKWebViewConfiguration"), "alloc"), "init");
        try {
            this.websiteDataStore = configureSession(configuration, config.webViewSession());
            this.userContentController =
                    ObjC.retain(ObjC.send(configuration, "userContentController"));
            this.scriptMessageHandler =
                    newPeerInstance(scriptMessageHandlerClass, "scriptMessageHandler");
            ObjC.sendVoid(userContentController, "addScriptMessageHandler:name:",
                    scriptMessageHandler, ObjC.nsString("jdesk"));

            String[] userScripts = config.consoleCapture()
                    ? new String[] {INIT_SCRIPT, InitScripts.CONSOLE_CAPTURE}
                    : new String[] {INIT_SCRIPT};
            for (String source : userScripts) {
                MemorySegment userScript;
                try {
                    userScript = (MemorySegment) ObjC.msgSend(INIT_USER_SCRIPT_DESC).invokeExact(
                            ObjC.send(ObjC.cls("WKUserScript"), "alloc"),
                            ObjC.sel("initWithSource:injectionTime:forMainFrameOnly:"),
                            ObjC.nsString(source), WK_USER_SCRIPT_AT_DOCUMENT_START, (byte) 1);
                } catch (Throwable t) {
                    throw ObjC.rethrow(t);
                }
                ObjC.sendVoid(userContentController, "addUserScript:", userScript);
                ObjC.release(userScript);
            }

            this.schemeHandler = newPeerInstance(schemeHandlerClass, "urlSchemeHandler");
            ObjC.sendVoid(configuration, "setURLSchemeHandler:forURLScheme:",
                    schemeHandler, ObjC.nsString("jdesk"));

            MemorySegment view;
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment frame = confined.allocate(ObjC.NSRECT);
                frame.set(JAVA_DOUBLE, 16, config.width());
                frame.set(JAVA_DOUBLE, 24, config.height());
                // JDeskDropWebView (WKWebView subclass) adds native file-drop; identical
                // behaviour otherwise. Fall back to plain WKWebView if the subclass is
                // unavailable, so the web view is never at risk.
                MemorySegment webViewClass;
                try {
                    webViewClass = MacFileDrop.webViewClass();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "JDeskDropWebView unavailable; file-drop disabled", e);
                    webViewClass = ObjC.cls("WKWebView");
                }
                view = (MemorySegment) ObjC.msgSend(INIT_WEBVIEW_DESC).invokeExact(
                        ObjC.send(webViewClass, "alloc"),
                        ObjC.sel("initWithFrame:configuration:"), frame, configuration);
            } catch (Throwable t) {
                throw ObjC.rethrow(t);
            }
            if (view.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("WKWebView initialization failed");
            }
            this.webView = view;
        } catch (RuntimeException e) {
            ObjC.release(configuration);
            throw e;
        }
        ObjC.release(configuration); // the view holds its own reference

        config.webViewSession().userAgent().ifPresent(userAgent ->
                ObjC.sendVoid(webView, "setCustomUserAgent:", ObjC.nsString(userAgent)));

        this.navigationDelegate = newPeerInstance(navigationDelegateClass, "navigationDelegate");
        ObjC.sendVoid(webView, "setNavigationDelegate:", navigationDelegate);

        boolean actualDevToolsEnabled = false;
        if (config.devToolsEnabled()
                && ObjC.sendBool(webView, "respondsToSelector:", ObjC.sel("setInspectable:"))) {
            // Public API since macOS 13.3; enables Safari Web Inspector attachment.
            ObjC.sendVoidBool(webView, "setInspectable:", true);
            actualDevToolsEnabled = ObjC.sendBool(webView, "isInspectable");
        }
        this.devToolsEnabled = actualDevToolsEnabled;
    }

    private MemorySegment configureSession(MemorySegment configuration,
            WebViewSessionConfig session) {
        MemorySegment dataStore = app.websiteDataStore(session);
        ObjC.sendVoid(configuration, "setWebsiteDataStore:", dataStore);
        return dataStore;
    }

    private MemorySegment newPeerInstance(MemorySegment cls, String name) {
        MemorySegment instance = ObjC.send(ObjC.send(cls, "alloc"), "init");
        if (instance.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Failed to instantiate " + name);
        }
        PEERS.put(instance.address(), this);
        registry.register(new NativeCallbackRegistry.Registration(
                name, this, MethodHandles.constant(Object.class, null), instance, null,
                () -> PEERS.remove(instance.address())));
        return instance;
    }

    private static void ensureClasses() {
        synchronized (CLASS_LOCK) {
            if (scriptMessageHandlerClass != null) {
                return;
            }
            FunctionDescriptor v3 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
            FunctionDescriptor v4 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
            FunctionDescriptor v5 =
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
            MethodType mt3 = MethodType.methodType(void.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class);
            MethodType mt4 = MethodType.methodType(void.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class, MemorySegment.class);
            MethodType mt5 = MethodType.methodType(void.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class, MemorySegment.class,
                    MemorySegment.class);
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                scriptMessageHandlerClass = new ObjCClassBuilder("JDeskScriptMessageHandler")
                        .protocol("WKScriptMessageHandler")
                        .method("userContentController:didReceiveScriptMessage:", "v@:@@", v4,
                                lookup.findStatic(MacWebView.class,
                                        "impDidReceiveScriptMessage", mt4))
                        .register();
                schemeHandlerClass = new ObjCClassBuilder("JDeskURLSchemeHandler")
                        .protocol("WKURLSchemeHandler")
                        .method("webView:startURLSchemeTask:", "v@:@@", v4,
                                lookup.findStatic(MacWebView.class, "impStartUrlSchemeTask", mt4))
                        .method("webView:stopURLSchemeTask:", "v@:@@", v4,
                                lookup.findStatic(MacWebView.class, "impStopUrlSchemeTask", mt4))
                        .register();
                navigationDelegateClass = new ObjCClassBuilder("JDeskNavigationDelegate")
                        .protocol("WKNavigationDelegate")
                        .method("webView:decidePolicyForNavigationAction:decisionHandler:",
                                "v@:@@@?", v5,
                                lookup.findStatic(MacWebView.class, "impDecidePolicy", mt5))
                        .method("webView:didCommitNavigation:", "v@:@@", v4,
                                lookup.findStatic(MacWebView.class, "impDidCommitNavigation", mt4))
                        .method("webViewWebContentProcessDidTerminate:", "v@:@", v3,
                                lookup.findStatic(MacWebView.class,
                                        "impContentProcessDidTerminate", mt3))
                        .register();
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    MemorySegment nsView() {
        return webView;
    }

    /** Pops up a native context menu (modal, main thread) and returns the chosen id. */
    java.util.Optional<String> showContextMenu(dev.jdesk.api.MenuSpec spec) {
        java.util.concurrent.atomic.AtomicReference<String> selected =
                new java.util.concurrent.atomic.AtomicReference<>();
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment menu = MacMenu.buildStandaloneMenu(spec, selected::set);
            MemorySegment location = arena.allocate(ObjC.NSPOINT); // zero-initialized: view origin
            byte unused = (byte) ObjC.msgSend(FunctionDescriptor.of(
                    JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ObjC.NSPOINT, ADDRESS)).invokeExact(
                    menu, ObjC.sel("popUpMenuPositioningItem:atLocation:inView:"),
                    MemorySegment.NULL, location, webView);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
        return java.util.Optional.ofNullable(selected.get());
    }

    /** Registers an OS file-drop listener on this view; returns an unsubscribe action. */
    Runnable onFileDrop(java.util.function.Consumer<java.util.List<java.nio.file.Path>> listener) {
        return MacFileDrop.register(webView, listener);
    }

    // ---- IMP bodies (main thread; copy data, never block, never throw across FFM) ----

    @SuppressWarnings("unused") // IMP upcall
    static void impDidReceiveScriptMessage(MemorySegment self, MemorySegment cmd,
            MemorySegment controller, MemorySegment message) {
        MacWebView peer = PEERS.get(self.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return;
        }
        try {
            MemorySegment body = ObjC.send(message, "body");
            if (body.equals(MemorySegment.NULL)) {
                return;
            }
            String text = ObjC.sendBool(body, "isKindOfClass:", ObjC.cls("NSString"))
                    ? ObjC.javaString(body)
                    : ObjC.javaString(ObjC.send(body, "description"));
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

    @SuppressWarnings("unused") // IMP upcall
    static void impStartUrlSchemeTask(MemorySegment self, MemorySegment cmd,
            MemorySegment webView, MemorySegment task) {
        MacWebView peer = PEERS.get(self.address());
        if (peer == null || !peer.registry.gate().enter()) {
            failTaskQuietly(task, "web view closed");
            return;
        }
        try {
            peer.beginServeTask(task);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Asset interception failed", t);
            failTaskQuietly(task, "asset interception failed");
        } finally {
            peer.registry.gate().exit();
        }
    }

    @SuppressWarnings("unused") // IMP upcall
    static void impStopUrlSchemeTask(MemorySegment self, MemorySegment cmd,
            MemorySegment webView, MemorySegment task) {
        // Resolution/streaming runs on a background thread; flag the task so no further
        // WKURLSchemeTask callback is issued for it (calling after stop would throw).
        MacWebView peer = PEERS.get(self.address());
        if (peer != null) {
            AtomicBoolean cancelled = peer.inflightTasks.get(task.address());
            if (cancelled != null) {
                cancelled.set(true);
            }
        }
    }

    @SuppressWarnings("unused") // IMP upcall
    static void impDecidePolicy(MemorySegment self, MemorySegment cmd, MemorySegment webView,
            MemorySegment action, MemorySegment decisionHandler) {
        long policy = WK_NAVIGATION_POLICY_ALLOW;
        MacWebView peer = PEERS.get(self.address());
        if (peer != null && peer.registry.gate().enter()) {
            try {
                policy = peer.decidePolicy(action);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "Navigation policy decision failed; blocking", t);
                policy = WK_NAVIGATION_POLICY_CANCEL;
            } finally {
                peer.registry.gate().exit();
            }
        }
        try {
            // The decision handler must be called exactly once on every path.
            ObjCBlock.invokeWithLong(decisionHandler, policy);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "decisionHandler invocation failed", t);
        }
    }

    @SuppressWarnings("unused") // IMP upcall
    static void impDidCommitNavigation(MemorySegment self, MemorySegment cmd,
            MemorySegment webView, MemorySegment navigation) {
        MacWebView peer = PEERS.get(self.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return;
        }
        try {
            URI uri = peer.currentUrl();
            LOG.log(Level.INFO, "didCommitNavigation url={0}", uri);
            for (Consumer<URI> listener : peer.committedListeners) {
                listener.accept(uri);
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "didCommitNavigation handling failed", t);
        } finally {
            peer.registry.gate().exit();
        }
    }

    @SuppressWarnings("unused") // IMP upcall
    static void impContentProcessDidTerminate(MemorySegment self, MemorySegment cmd,
            MemorySegment webView) {
        MacWebView peer = PEERS.get(self.address());
        if (peer == null || !peer.registry.gate().enter()) {
            return;
        }
        try {
            WebViewProcessFailure failure = new WebViewProcessFailure(
                    WebViewProcessFailure.Kind.RENDER_PROCESS_EXITED,
                    "webViewWebContentProcessDidTerminate");
            LOG.log(Level.ERROR, "WKWebView process failure: {0}", failure);
            for (Consumer<WebViewProcessFailure> listener : peer.failureListeners) {
                listener.accept(failure);
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Process-failure handling failed", t);
        } finally {
            peer.registry.gate().exit();
        }
    }

    private long decidePolicy(MemorySegment action) {
        MemorySegment targetFrame = ObjC.send(action, "targetFrame");
        if (targetFrame.equals(MemorySegment.NULL)) {
            // New-window/popup target: denied by default (spec 12.2).
            LOG.log(Level.WARNING, "Blocked popup/new-window navigation request");
            return WK_NAVIGATION_POLICY_CANCEL;
        }
        boolean mainFrame = ObjC.sendBool(targetFrame, "isMainFrame");
        String url = ObjC.javaString(
                ObjC.send(ObjC.send(ObjC.send(action, "request"), "URL"), "absoluteString"));
        // Best-effort flag (not a security boundary): link activation or form submission.
        long navigationType = ObjC.sendLong(action, "navigationType");
        boolean userInitiated = navigationType == 0 || navigationType == 1;
        NavigationRequest request;
        try {
            request = new NavigationRequest(URI.create(url), mainFrame, userInitiated);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Blocked navigation to unparseable target");
            return WK_NAVIGATION_POLICY_CANCEL;
        }
        for (NavigationListener listener : navigationListeners) {
            if (listener.onNavigate(request) == NavigationDecision.BLOCK) {
                LOG.log(Level.INFO, "decidePolicy BLOCK main={0} uri={1}", mainFrame, url);
                return WK_NAVIGATION_POLICY_CANCEL;
            }
        }
        LOG.log(Level.INFO, "decidePolicy ALLOW main={0} uri={1}", mainFrame, url);
        return WK_NAVIGATION_POLICY_ALLOW;
    }

    private URI currentUrl() {
        try {
            String url = ObjC.javaString(
                    ObjC.send(ObjC.send(webView, "URL"), "absoluteString"));
            return url == null || url.isEmpty() ? URI.create("about:blank") : URI.create(url);
        } catch (RuntimeException e) {
            return URI.create("about:blank");
        }
    }

    // ---- asset serving (WKURLSchemeHandler, spec section 9) ----
    //
    // Resolution and body streaming run on a virtual thread so slow handlers (disk,
    // app-defined proxy routes doing network I/O) never block the main thread; every
    // WKURLSchemeTask callback is marshalled back to the main thread and skipped once
    // the engine has stopped the task.

    /** Reads request facts on the main thread, then hands off to a virtual thread. */
    private void beginServeTask(MemorySegment task) {
        MemorySegment request = ObjC.send(task, "request");
        MemorySegment nsUrl = ObjC.send(request, "URL");
        String url = ObjC.javaString(ObjC.send(nsUrl, "absoluteString"));
        String method0 = ObjC.javaString(ObjC.send(request, "HTTPMethod"));
        String method = method0 == null || method0.isEmpty() ? "GET" : method0;
        // Forward the Range header so the resolver can serve 206 partial content —
        // required for media seeking (AVFoundation issues ranged requests).
        String range = ObjC.javaString(
                ObjC.send(request, "valueForHTTPHeaderField:", ObjC.nsString("Range")));
        Map<String, String> requestHeaders =
                range == null || range.isEmpty() ? Map.of() : Map.of("Range", range);
        // Read the upload body here, on the main thread where the NSURLRequest is live, so
        // a page fetch(..., {method:'POST', body}) reaches the resolver as raw bytes. Bounded
        // to the upload cap + 1 so an oversize body becomes a 413, never an unbounded copy.
        byte[] body = readRequestBody(request, method);

        ObjC.retain(task);
        ObjC.retain(nsUrl);
        AtomicBoolean cancelled = new AtomicBoolean();
        inflightTasks.put(task.address(), cancelled);
        Thread.ofVirtual().name("jdesk-asset-task").start(() ->
                serveTaskAsync(task, nsUrl, url, method, body, requestHeaders, cancelled));
    }

    private static final byte[] EMPTY_BODY = new byte[0];
    /** Largest byte[] the JVM will allocate for a request body slice. */
    private static final long MAX_BODY_ARRAY = Integer.MAX_VALUE - 8L;

    /**
     * Reads an uploaded request body for methods that carry one. WebKit delivers a page
     * {@code fetch(..., {method:'POST', body})} to a WKURLSchemeHandler through the
     * NSURLRequest's {@code HTTPBody} (in-memory) or {@code HTTPBodyStream} (streamed);
     * either may be nil. Bounded to {@link AssetRequest#MAX_BODY_BYTES}+1 so the resolver
     * answers 413 without buffering an unbounded upload.
     */
    private byte[] readRequestBody(MemorySegment request, String method) {
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)
                && !"PATCH".equalsIgnoreCase(method)) {
            return EMPTY_BODY;
        }
        long limit = Math.min(AssetRequest.MAX_BODY_BYTES + 1, MAX_BODY_ARRAY);
        MemorySegment httpBody = ObjC.send(request, "HTTPBody");
        if (httpBody != null && !httpBody.equals(MemorySegment.NULL)) {
            byte[] read = copyNsData(httpBody, limit);
            LOG.log(Level.DEBUG, "upload body via HTTPBody: {0} bytes", read.length);
            return read;
        }
        MemorySegment stream = ObjC.send(request, "HTTPBodyStream");
        if (stream != null && !stream.equals(MemorySegment.NULL)) {
            byte[] read = readNsInputStream(stream, limit);
            LOG.log(Level.DEBUG, "upload body via HTTPBodyStream: {0} bytes", read.length);
            return read;
        }
        LOG.log(Level.DEBUG, "upload body absent (no HTTPBody/HTTPBodyStream)");
        return EMPTY_BODY;
    }

    /** Copies up to {@code limit} bytes out of an NSData ({@code -bytes}/{@code -length}). */
    private byte[] copyNsData(MemorySegment data, long limit) {
        long length = ObjC.sendLong(data, "length");
        if (length <= 0) {
            return EMPTY_BODY;
        }
        int count = (int) Math.min(length, limit);
        MemorySegment bytes = ObjC.send(data, "bytes");
        if (bytes.equals(MemorySegment.NULL)) {
            return EMPTY_BODY;
        }
        return bytes.reinterpret(count).toArray(JAVA_BYTE);
    }

    /** Drains up to {@code limit} bytes from an NSInputStream (open/read:maxLength:/close). */
    private byte[] readNsInputStream(MemorySegment stream, long limit) {
        ObjC.sendVoid(stream, "open");
        try (Arena arena = Arena.ofConfined()) {
            int chunkSize = 64 * 1024;
            MemorySegment chunk = arena.allocate(chunkSize);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (out.size() < limit) {
                long want = Math.min(chunkSize, limit - out.size());
                long read = ObjC.sendReadMaxLength(stream, chunk, want);
                if (read <= 0) {
                    break; // 0 = end of stream, -1 = stream error
                }
                out.write(chunk.reinterpret(read).toArray(JAVA_BYTE), 0, (int) read);
            }
            return out.toByteArray();
        } finally {
            ObjC.sendVoid(stream, "close");
        }
    }

    private void serveTaskAsync(MemorySegment task, MemorySegment nsUrl, String url,
            String method, byte[] body, Map<String, String> requestHeaders,
            AtomicBoolean cancelled) {
        try {
            AssetResponse asset;
            try {
                asset = app.config().assetHandler().handle(
                        new AssetRequest(URI.create(url), method, body, requestHeaders));
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Asset handler failed for {0}", url, e);
                onMain(task, cancelled, () -> failTaskQuietly(task, "asset resolution failed"));
                return;
            }
            LOG.log(Level.INFO, "asset {0} {1} -> {2}", method, url, asset.status());

            if (!onMain(task, cancelled, () -> deliverResponseHeader(task, nsUrl, asset))) {
                return;
            }
            // Stream the body in bounded chunks (spec 9.1: never buffer whole assets).
            // Deliveries are joined one at a time, so the single shared-arena buffer is
            // never touched concurrently and a slow consumer naturally paces the reader.
            try (Arena bodyArena = Arena.ofShared(); InputStream responseBody = asset.body().get()) {
                MemorySegment nativeChunk = bodyArena.allocate(ASYNC_CHUNK_SIZE);
                byte[] chunk = new byte[ASYNC_CHUNK_SIZE];
                int read;
                while ((read = responseBody.read(chunk)) > 0) {
                    int length = read;
                    MemorySegment.copy(MemorySegment.ofArray(chunk), 0, nativeChunk, 0, length);
                    if (!onMain(task, cancelled, () -> deliverChunk(task, nativeChunk, length))) {
                        return;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // RuntimeException covers unchecked failures from app-supplied bodies,
                // e.g. UncheckedIOException out of AssetRoute.Response.of(Path).
                LOG.log(Level.ERROR, "Asset body streaming failed for {0}", url, e);
                onMain(task, cancelled, () -> failTaskQuietly(task, "asset body streaming failed"));
                return;
            }
            onMain(task, cancelled, () -> ObjC.sendVoid(task, "didFinish"));
        } finally {
            releaseTaskQuietly(task, nsUrl);
        }
    }

    /** Releases the retained task refs on the main thread; a shutdown race only leaks. */
    private void releaseTaskQuietly(MemorySegment task, MemorySegment nsUrl) {
        try {
            app.ui().execute(() -> {
                inflightTasks.remove(task.address());
                ObjC.release(nsUrl);
                ObjC.release(task);
            });
        } catch (RuntimeException e) {
            // UI dispatcher is gone (shutdown): the native refs cannot be released
            // safely off the main thread; drop the tracking entry and accept the
            // one-off leak instead of throwing on the streaming thread.
            inflightTasks.remove(task.address());
            LOG.log(Level.DEBUG, "Scheme task release skipped during shutdown", e);
        }
    }

    /**
     * Runs one WKURLSchemeTask callback on the main thread. Returns false — and issues
     * nothing further — once the engine stopped the task or the view is closing;
     * callbacks after stopURLSchemeTask would throw inside WebKit. A callback that
     * itself throws fails the task (while it is still legal to do so) and stops the
     * stream, so the page never waits forever on an abandoned request.
     */
    private boolean onMain(MemorySegment task, AtomicBoolean cancelled, Runnable action) {
        try {
            return app.ui().submit(() -> {
                if (cancelled.get() || !registry.gate().enter()) {
                    return false;
                }
                try {
                    action.run();
                    return true;
                } catch (Throwable t) {
                    LOG.log(Level.ERROR, "Scheme task callback failed", t);
                    cancelled.set(true);
                    failTaskQuietly(task, "asset delivery failed");
                    return false;
                } finally {
                    registry.gate().exit();
                }
            }).toCompletableFuture().join();
        } catch (RuntimeException e) {
            return false; // dispatcher rejected the submit (shutdown)
        }
    }

    private void deliverResponseHeader(MemorySegment task, MemorySegment nsUrl,
            AssetResponse asset) {
        Map<String, String> headers = new LinkedHashMap<>(asset.headers());
        if (asset.contentLength() >= 0) {
            headers.putIfAbsent("Content-Length", Long.toString(asset.contentLength()));
        }
        MemorySegment headerDict;
        try {
            headerDict = (MemorySegment) ObjC.msgSend(DICT_WITH_CAPACITY_DESC).invokeExact(
                    ObjC.cls("NSMutableDictionary"), ObjC.sel("dictionaryWithCapacity:"),
                    (long) headers.size());
            for (Map.Entry<String, String> header : headers.entrySet()) {
                ObjC.sendVoid(headerDict, "setObject:forKey:",
                        ObjC.nsString(header.getValue()), ObjC.nsString(header.getKey()));
            }
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }

        MemorySegment response;
        try {
            response = (MemorySegment) ObjC.msgSend(INIT_HTTP_RESPONSE_DESC).invokeExact(
                    ObjC.send(ObjC.cls("NSHTTPURLResponse"), "alloc"),
                    ObjC.sel("initWithURL:statusCode:HTTPVersion:headerFields:"),
                    nsUrl, (long) asset.status(), ObjC.nsString("HTTP/1.1"), headerDict);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
        ObjC.sendVoid(task, "didReceiveResponse:", response);
        ObjC.release(response);
    }

    private void deliverChunk(MemorySegment task, MemorySegment nativeChunk, int length) {
        MemorySegment data;
        try {
            data = (MemorySegment) ObjC.msgSend(DATA_WITH_BYTES_DESC).invokeExact(
                    ObjC.cls("NSData"), ObjC.sel("dataWithBytes:length:"),
                    nativeChunk, (long) length);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
        ObjC.sendVoid(task, "didReceiveData:", data);
    }

    private static void failTaskQuietly(MemorySegment task, String message) {
        try {
            MemorySegment error = (MemorySegment) ObjC.msgSend(ERROR_WITH_DOMAIN_DESC).invokeExact(
                    ObjC.cls("NSError"), ObjC.sel("errorWithDomain:code:userInfo:"),
                    ObjC.nsString("dev.jdesk"), 1L, MemorySegment.NULL);
            ObjC.sendVoid(task, "didFailWithError:", error);
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "didFailWithError failed after: {0}", message);
        }
    }

    // ---- PlatformWebView ----

    @Override
    public void navigate(URI uri) {
        MemorySegment nsUrl = ObjC.send(ObjC.cls("NSURL"), "URLWithString:",
                ObjC.nsString(uri.toString()));
        MemorySegment request = ObjC.send(ObjC.cls("NSURLRequest"), "requestWithURL:", nsUrl);
        MemorySegment unusedNavigation = ObjC.send(webView, "loadRequest:", request);
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
        // Nil completion handler is public API; no per-message upcall stub is created.
        ObjC.sendVoid(webView, "evaluateJavaScript:completionHandler:",
                ObjC.nsString(script), MemorySegment.NULL);
    }

    @Override
    public CompletionStage<String> evaluate(String script) {
        CompletableFuture<String> future = new CompletableFuture<>();
        MemorySegment completion = ObjCBlock.create2(app.blockRegistry(),
                "evaluateJavaScriptCompletion", (block, result, error) -> {
                    if (!error.equals(MemorySegment.NULL)) {
                        future.completeExceptionally(new IllegalStateException(
                                "evaluateJavaScript failed: " + describeError(error)));
                        return;
                    }
                    future.complete(describeResult(result));
                });
        try {
            ObjC.sendVoid(webView, "evaluateJavaScript:completionHandler:",
                    ObjC.nsString(script), completion);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static String describeResult(MemorySegment result) {
        if (result.equals(MemorySegment.NULL)) {
            return "null";
        }
        if (ObjC.sendBool(result, "isKindOfClass:", ObjC.cls("NSString"))) {
            return ObjC.javaString(result);
        }
        String description = ObjC.javaString(ObjC.send(result, "description"));
        return description == null ? "null" : description;
    }

    private static String describeError(MemorySegment error) {
        try {
            String localized = ObjC.javaString(ObjC.send(error, "localizedDescription"));
            String description = ObjC.javaString(ObjC.send(error, "description"));
            if (description != null && !description.equals(localized)) {
                return (localized == null ? "unknown error" : localized)
                        + " (" + description + ")";
            }
            return localized == null ? "unknown error" : localized;
        } catch (RuntimeException e) {
            return "unknown error";
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
        MemorySegment completion = ObjCBlock.create2(app.blockRegistry(),
                "takeSnapshotCompletion", (block, image, error) -> {
                    try {
                        if (image.equals(MemorySegment.NULL)) {
                            future.completeExceptionally(new IllegalStateException(
                                    "takeSnapshot failed: " + describeError(error)));
                            return;
                        }
                        future.complete(encodePng(image));
                    } catch (Throwable t) {
                        future.completeExceptionally(
                                new IllegalStateException("snapshot conversion failed", t));
                    }
                });
        try {
            // nil configuration captures the visible viewport (documented default).
            ObjC.sendVoid(webView, "takeSnapshotWithConfiguration:completionHandler:",
                    MemorySegment.NULL, completion);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** NSImage -> CGImage -> NSBitmapImageRep -> PNG NSData -> Java bytes. */
    private static WebViewSnapshot encodePng(MemorySegment image) {
        MemorySegment cgImage;
        try {
            cgImage = (MemorySegment) ObjC.msgSend(CGIMAGE_DESC).invokeExact(
                    image, ObjC.sel("CGImageForProposedRect:context:hints:"),
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
        if (cgImage.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Snapshot NSImage has no CGImage");
        }
        MemorySegment rep = ObjC.send(
                ObjC.send(ObjC.cls("NSBitmapImageRep"), "alloc"), "initWithCGImage:", cgImage);
        if (rep.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("NSBitmapImageRep creation failed");
        }
        try {
            long width = ObjC.sendLong(rep, "pixelsWide");
            long height = ObjC.sendLong(rep, "pixelsHigh");
            MemorySegment properties = ObjC.send(ObjC.cls("NSDictionary"), "dictionary");
            MemorySegment data;
            try {
                data = (MemorySegment) ObjC.msgSend(REPRESENTATION_DESC).invokeExact(
                        rep, ObjC.sel("representationUsingType:properties:"),
                        NS_BITMAP_FILE_TYPE_PNG, properties);
            } catch (Throwable t) {
                throw ObjC.rethrow(t);
            }
            if (data.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("PNG encoding failed");
            }
            MemorySegment bytes = ObjC.send(data, "bytes");
            long length = ObjC.sendLong(data, "length");
            byte[] png = new byte[Math.toIntExact(length)];
            MemorySegment.copy(bytes.reinterpret(length), 0,
                    MemorySegment.ofArray(png), 0, length);
            return new WebViewSnapshot((int) width, (int) height, png);
        } finally {
            ObjC.release(rep);
        }
    }

    @Override
    public WebViewDiagnostics diagnostics() {
        Optional<String> version = Optional.empty();
        try {
            MemorySegment bundle = ObjC.send(ObjC.cls("NSBundle"), "bundleWithPath:",
                    ObjC.nsString("/System/Library/Frameworks/WebKit.framework"));
            MemorySegment shortVersion = ObjC.send(bundle, "objectForInfoDictionaryKey:",
                    ObjC.nsString("CFBundleShortVersionString"));
            MemorySegment buildVersion = ObjC.send(bundle, "objectForInfoDictionaryKey:",
                    ObjC.nsString("CFBundleVersion"));
            String shortText = ObjC.javaString(shortVersion);
            String buildText = ObjC.javaString(buildVersion);
            if (shortText != null) {
                version = Optional.of("WebKit " + shortText
                        + (buildText == null ? "" : " (" + buildText + ")"));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Could not read WebKit bundle version", e);
        }
        return new WebViewDiagnostics(version, Optional.empty(), Optional.empty());
    }
    @Override public boolean devToolsEnabled(){return devToolsEnabled;}

    @Override
    public CompletionStage<Void> clearData(Set<WebViewDataType> dataTypes) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        MemorySegment nativeTypes = ObjC.send(ObjC.cls("NSMutableSet"), "set");
        if (dataTypes.contains(WebViewDataType.COOKIES)) {
            ObjC.sendVoid(nativeTypes, "addObject:",
                    ObjC.webKitObjectConstant("WKWebsiteDataTypeCookies"));
        }
        if (dataTypes.contains(WebViewDataType.CACHE)) {
            ObjC.sendVoid(nativeTypes, "addObject:",
                    ObjC.webKitObjectConstant("WKWebsiteDataTypeMemoryCache"));
            ObjC.sendVoid(nativeTypes, "addObject:",
                    ObjC.webKitObjectConstant("WKWebsiteDataTypeDiskCache"));
        }
        if (dataTypes.contains(WebViewDataType.LOCAL_STORAGE)) {
            ObjC.sendVoid(nativeTypes, "addObject:",
                    ObjC.webKitObjectConstant("WKWebsiteDataTypeLocalStorage"));
        }
        MemorySegment completion = ObjCBlock.create0(app.blockRegistry(),
                "clearWebsiteDataCompletion", block -> future.complete(null));
        try {
            ObjC.sendVoid(websiteDataStore,
                    "removeDataOfTypes:modifiedSince:completionHandler:",
                    nativeTypes, ObjC.send(ObjC.cls("NSDate"), "distantPast"), completion);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletionStage<List<WebViewCookie>> cookies() {
        CompletableFuture<List<WebViewCookie>> future = new CompletableFuture<>();
        MemorySegment completion = ObjCBlock.create1(app.blockRegistry(), "getAllCookiesCompletion",
                (block, array) -> {
                    try {
                        long count = ObjC.sendLong(array, "count");
                        List<WebViewCookie> result = new ArrayList<>(Math.toIntExact(count));
                        for (long i = 0; i < count; i++) {
                            result.add(javaCookie(ObjC.sendIndexed(array, "objectAtIndex:", i)));
                        }
                        future.complete(List.copyOf(result));
                    } catch (RuntimeException e) {
                        future.completeExceptionally(e);
                    }
                });
        try {
            ObjC.sendVoid(cookieStore(), "getAllCookies:", completion);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletionStage<Void> setCookie(WebViewCookie cookie) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        MemorySegment completion = ObjCBlock.create0(app.blockRegistry(), "setCookieCompletion",
                block -> future.complete(null));
        try {
            ObjC.sendVoid(cookieStore(), "setCookie:completionHandler:",
                    nativeCookie(cookie), completion);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletionStage<Void> deleteCookie(WebViewCookieKey key) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        MemorySegment deleted = ObjCBlock.create0(app.blockRegistry(), "deleteCookieCompletion",
                block -> future.complete(null));
        MemorySegment located = ObjCBlock.create1(app.blockRegistry(), "locateCookieForDelete",
                (block, array) -> {
                    try {
                        long count = ObjC.sendLong(array, "count");
                        for (long i = 0; i < count; i++) {
                            MemorySegment cookie = ObjC.sendIndexed(array, "objectAtIndex:", i);
                            if (key.name().equals(ObjC.javaString(ObjC.send(cookie, "name")))
                                    && key.domain().equals(ObjC.javaString(
                                            ObjC.send(cookie, "domain")))
                                    && key.path().equals(ObjC.javaString(
                                            ObjC.send(cookie, "path")))) {
                                ObjC.sendVoid(cookieStore(), "deleteCookie:completionHandler:",
                                        cookie, deleted);
                                return;
                            }
                        }
                        future.complete(null); // Missing keys are intentionally idempotent.
                    } catch (RuntimeException e) {
                        future.completeExceptionally(e);
                    }
                });
        try {
            ObjC.sendVoid(cookieStore(), "getAllCookies:", located);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private MemorySegment cookieStore() {
        return ObjC.send(websiteDataStore, "httpCookieStore");
    }

    private WebViewCookie javaCookie(MemorySegment cookie) {
        MemorySegment expiresDate = ObjC.send(cookie, "expiresDate");
        Optional<Instant> expiresAt = expiresDate.equals(MemorySegment.NULL)
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(Math.round(
                        ObjC.sendDouble(expiresDate, "timeIntervalSince1970") * 1_000.0)));
        return new WebViewCookie(
                ObjC.javaString(ObjC.send(cookie, "name")),
                ObjC.javaString(ObjC.send(cookie, "value")),
                ObjC.javaString(ObjC.send(cookie, "domain")),
                ObjC.javaString(ObjC.send(cookie, "path")),
                expiresAt,
                ObjC.sendBool(cookie, "isSecure"),
                ObjC.sendBool(cookie, "isHTTPOnly"));
    }

    private MemorySegment nativeCookie(WebViewCookie cookie) {
        StringBuilder header = new StringBuilder(cookie.name()).append('=').append(cookie.value());
        // NSHTTPCookie treats any explicit Domain attribute as a domain cookie and
        // canonicalizes it with a leading dot. Omit it for host-only identities so a
        // round trip preserves WebViewCookieKey.domain().
        if (cookie.domain().startsWith(".")) {
            header.append("; Domain=").append(cookie.domain());
        }
        header.append("; Path=").append(cookie.path());
        cookie.expiresAt().ifPresent(expires -> header.append("; Expires=").append(
                DateTimeFormatter.RFC_1123_DATE_TIME.format(expires.atZone(ZoneOffset.UTC))));
        if (cookie.secure()) {
            header.append("; Secure");
        }
        if (cookie.httpOnly()) {
            header.append("; HttpOnly");
        }
        MemorySegment fields = ObjC.send(ObjC.cls("NSDictionary"),
                "dictionaryWithObject:forKey:", ObjC.nsString(header.toString()),
                ObjC.nsString("Set-Cookie"));
        String host = cookie.domain().startsWith(".")
                ? cookie.domain().substring(1) : cookie.domain();
        MemorySegment url = ObjC.send(ObjC.cls("NSURL"), "URLWithString:",
                ObjC.nsString((cookie.secure() ? "https" : "http") + "://" + host
                        + cookie.path()));
        MemorySegment parsed = ObjC.send(ObjC.cls("NSHTTPCookie"),
                "cookiesWithResponseHeaderFields:forURL:", fields, url);
        if (ObjC.sendLong(parsed, "count") == 0) {
            throw new IllegalArgumentException("Foundation rejected the cookie");
        }
        return ObjC.sendIndexed(parsed, "objectAtIndex:", 0);
    }

    /** Called from the window's willClose path; detaches and releases the pipeline. */
    void destroyFromWindow() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ObjC.sendVoid(webView, "stopLoading");
            ObjC.sendVoid(userContentController, "removeScriptMessageHandlerForName:",
                    ObjC.nsString("jdesk"));
            ObjC.sendVoid(userContentController, "removeAllUserScripts");
            ObjC.sendVoid(webView, "setNavigationDelegate:", MemorySegment.NULL);
            ObjC.release(userContentController);
            // WebKit may still be delivering to these objects within the current event;
            // defer the owning releases to the enclosing autorelease pool.
            ObjC.autorelease(scriptMessageHandler);
            ObjC.autorelease(schemeHandler);
            ObjC.autorelease(navigationDelegate);
            ObjC.autorelease(webView);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "WKWebView teardown failed", e);
        }
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
