package dev.jdesk.platform.windows;

import dev.jdesk.api.Subscription;
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
import dev.jdesk.webview.spi.WindowBounds;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * WebView2 controller + core WebView for one window. All calls are UI-thread only; the
 * runtime marshals through {@link dev.jdesk.api.UiDispatcher}. Asset requests for
 * {@code jdesk://app} are intercepted with WebResourceRequested and answered from the
 * runtime {@link dev.jdesk.webview.spi.AssetHandler} — no sockets anywhere.
 */
final class WindowsWebView implements PlatformWebView {
    private static final Logger LOG = System.getLogger(WindowsWebView.class.getName());

    /**
     * Uniform bridge contract shared by all adapters: window.__jdesk.post(string) sends;
     * incoming strings arrive as 'jdesk-message' CustomEvents on document.
     */
    private static final String INIT_SCRIPT = """
            (function () {
              if (window.__jdesk) return;
              window.__jdesk = {
                nonce: null,
                post: function (s) { window.chrome.webview.postMessage(String(s)); }
              };
              window.chrome.webview.addEventListener('message', function (e) {
                var data = typeof e.data === 'string' ? e.data : JSON.stringify(e.data);
                // The nonce control envelope can arrive before page scripts attach
                // their listeners; capture it here so it is never lost.
                try {
                  var m = JSON.parse(data);
                  if (m && m.kind === 'nonce' && typeof m.nonce === 'string') {
                    window.__jdesk.nonce = m.nonce;
                  }
                } catch (err) { }
                document.dispatchEvent(new CustomEvent('jdesk-message', { detail: data }));
              });
            })();
            """;

    private static final FunctionDescriptor THIS_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor THIS_PTR_PTR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor THIS_INT =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor REMOVE_TOKEN =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG);

    private final WindowsPlatformApplication app;
    private final WindowsWindow window;
    private final NativeCallbackRegistry registry;
    private final MemorySegment controller; // owned
    private final MemorySegment webView;    // owned
    private final WebView2Environment environment;
    private final boolean devToolsEnabled;
    private final List<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<NavigationListener> navigationListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<URI>> committedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WebViewProcessFailure>> failureListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    WindowsWebView(WindowsPlatformApplication app, WindowsWindow window,
            NativeWindowConfig config) {
        this.app = app;
        this.window = window;
        this.registry = window.callbackRegistry();
        this.environment = app.environment(config.webViewSession());

        // --- controller (async) ---
        AtomicReference<MemorySegment> controllerRef = new AtomicReference<>();
        AtomicInteger completionHr = new AtomicInteger(1);
        MemorySegment controllerHandler = ComCallback.hrPtrHandler(registry,
                "ControllerCompletedHandler", WebView2.IID_CONTROLLER_COMPLETED_HANDLER,
                (self, hr, ctrl) -> {
                    if (hr >= 0 && !ctrl.equals(MemorySegment.NULL)) {
                        ComRuntime.addRef(ctrl);
                        controllerRef.set(ctrl);
                    }
                    completionHr.set(hr);
                    return Hresult.S_OK;
                });
        ComRuntime.invokeChecked(environment.comPointer(),
                WebView2.ENV_CREATE_CONTROLLER, "CreateCoreWebView2Controller",
                THIS_PTR_PTR, window.hwnd(), controllerHandler);
        app.pumpUntil(() -> completionHr.get() != 1, 60_000, "WebView2 controller");
        Hresult.check(completionHr.get(), "WebView2 controller completion");
        this.controller = controllerRef.get();

        // --- core web view ---
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment out = confined.allocate(ADDRESS);
            ComRuntime.invokeChecked(controller, WebView2.CTRL_GET_COREWEBVIEW2,
                    "get_CoreWebView2", THIS_PTR, out);
            MemorySegment wv = out.get(ADDRESS, 0);
            ComRuntime.addRef(wv);
            this.webView = wv;
        }

        this.devToolsEnabled = configureSettings(config.devToolsEnabled(),
                config.webViewSession().userAgent());
        installInitScript(config.consoleCapture());
        registerEventHandlers();
        registerResourceInterception();
        resizeToClientArea();
        ComRuntime.invokeChecked(controller, WebView2.CTRL_PUT_IS_VISIBLE,
                "put_IsVisible", THIS_INT, 1);
    }

    private boolean configureSettings(boolean devTools, java.util.Optional<String> userAgent) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment out = confined.allocate(ADDRESS);
            ComRuntime.invokeChecked(webView, WebView2.WV_GET_SETTINGS, "get_Settings",
                    THIS_PTR, out);
            MemorySegment settings = out.get(ADDRESS, 0);
            try {
                ComRuntime.invokeChecked(settings, WebView2.SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED,
                        "put_AreDevToolsEnabled", THIS_INT, devTools ? 1 : 0);
                if (userAgent.isPresent()) {
                    MemorySegment settings2 = ComRuntime.queryInterface(settings,
                            WebView2.IID_SETTINGS2);
                    try {
                        ComRuntime.invokeChecked(settings2, WebView2.SETTINGS2_PUT_USER_AGENT,
                                "put_UserAgent", THIS_PTR,
                                WideStrings.alloc(confined, userAgent.orElseThrow()));
                    } finally {
                        ComRuntime.release(settings2);
                    }
                }
                MemorySegment actual = confined.allocate(JAVA_INT);
                ComRuntime.invokeChecked(settings,
                        WebView2.SETTINGS_GET_ARE_DEV_TOOLS_ENABLED,
                        "get_AreDevToolsEnabled", THIS_PTR, actual);
                return actual.get(JAVA_INT, 0) != 0;
            } finally {
                ComRuntime.release(settings);
            }
        }
    }

    private void installInitScript(boolean consoleCapture) {
        installDocumentStartScript(INIT_SCRIPT);
        if (consoleCapture) {
            installDocumentStartScript(InitScripts.CONSOLE_CAPTURE);
        }
    }

    private void installDocumentStartScript(String script) {
        AtomicInteger done = new AtomicInteger(1);
        MemorySegment handler = ComCallback.hrPtrHandler(registry,
                "AddScriptCompletedHandler", WebView2.IID_ADD_SCRIPT_COMPLETED_HANDLER,
                (self, hr, idPtr) -> {
                    done.set(hr);
                    return Hresult.S_OK;
                });
        try (Arena confined = Arena.ofConfined()) {
            ComRuntime.invokeChecked(webView, WebView2.WV_ADD_SCRIPT_ON_DOCUMENT_CREATED,
                    "AddScriptToExecuteOnDocumentCreated", THIS_PTR_PTR,
                    WideStrings.alloc(confined, script), handler);
        }
        app.pumpUntil(() -> done.get() != 1, 30_000, "init script installation");
        Hresult.check(done.get(), "AddScriptToExecuteOnDocumentCreated completion");
    }

    private void registerEventHandlers() {
        // WebMessageReceived
        MemorySegment messageHandler = ComCallback.ptrPtrHandler(registry,
                "WebMessageReceivedHandler", WebView2.IID_WEB_MESSAGE_RECEIVED_HANDLER,
                (self, sender, args) -> {
                    try (Arena confined = Arena.ofConfined()) {
                        MemorySegment out = confined.allocate(ADDRESS);
                        int hr = (int) ComRuntime.invoke(args, WebView2.MSG_ARGS_TRY_GET_STRING,
                                THIS_PTR, out);
                        if (hr < 0) {
                            return Hresult.S_OK; // non-string message: ignore
                        }
                        String message = WideStrings.readAndFreeCoTaskMem(out.get(ADDRESS, 0));
                        LOG.log(Level.INFO, "bridge<- {0} chars", message.length());
                        for (Consumer<String> listener : messageListeners) {
                            listener.accept(message);
                        }
                    }
                    return Hresult.S_OK;
                });
        addEvent(WebView2.WV_ADD_WEB_MESSAGE_RECEIVED, "add_WebMessageReceived", messageHandler);

        // NavigationStarting (main frame)
        MemorySegment navigationHandler = ComCallback.ptrPtrHandler(registry,
                "NavigationStartingHandler", WebView2.IID_NAVIGATION_STARTING_HANDLER,
                (self, sender, args) -> handleNavigationStarting(args, true));
        addEvent(WebView2.WV_ADD_NAVIGATION_STARTING, "add_NavigationStarting", navigationHandler);

        // FrameNavigationStarting (subframes)
        MemorySegment frameNavigationHandler = ComCallback.ptrPtrHandler(registry,
                "FrameNavigationStartingHandler", WebView2.IID_NAVIGATION_STARTING_HANDLER,
                (self, sender, args) -> handleNavigationStarting(args, false));
        addEvent(WebView2.WV_ADD_FRAME_NAVIGATION_STARTING, "add_FrameNavigationStarting",
                frameNavigationHandler);

        // ContentLoading -> navigation committed (init script installed, page scripts not run)
        MemorySegment contentLoadingHandler = ComCallback.ptrPtrHandler(registry,
                "ContentLoadingHandler", WebView2.IID_CONTENT_LOADING_HANDLER,
                (self, sender, args) -> {
                    URI uri = currentSource();
                    LOG.log(Level.INFO, "contentLoading source={0}", uri);
                    for (Consumer<URI> listener : committedListeners) {
                        listener.accept(uri);
                    }
                    return Hresult.S_OK;
                });
        addEvent(WebView2.WV_ADD_CONTENT_LOADING, "add_ContentLoading", contentLoadingHandler);

        // NavigationCompleted: diagnostics only (success flag + web error status)
        MemorySegment navigationCompletedHandler = ComCallback.ptrPtrHandler(registry,
                "NavigationCompletedHandler", WebView2.IID_NAVIGATION_COMPLETED_HANDLER,
                (self, sender, args) -> {
                    try (Arena confined = Arena.ofConfined()) {
                        MemorySegment ok = confined.allocate(JAVA_INT);
                        ComRuntime.invoke(args, 3, THIS_PTR, ok); // get_IsSuccess
                        MemorySegment status = confined.allocate(JAVA_INT);
                        ComRuntime.invoke(args, 4, THIS_PTR, status); // get_WebErrorStatus
                        LOG.log(Level.INFO, "navigationCompleted success={0} webErrorStatus={1}",
                                ok.get(JAVA_INT, 0) != 0, status.get(JAVA_INT, 0));
                    }
                    return Hresult.S_OK;
                });
        addEvent(WebView2.WV_ADD_NAVIGATION_COMPLETED, "add_NavigationCompleted",
                navigationCompletedHandler);

        // ProcessFailed
        MemorySegment processFailedHandler = ComCallback.ptrPtrHandler(registry,
                "ProcessFailedHandler", WebView2.IID_PROCESS_FAILED_HANDLER,
                (self, sender, args) -> {
                    int kind;
                    try (Arena confined = Arena.ofConfined()) {
                        MemorySegment out = confined.allocate(JAVA_INT);
                        ComRuntime.invoke(args, WebView2.PF_ARGS_GET_KIND, THIS_PTR, out);
                        kind = out.get(JAVA_INT, 0);
                    }
                    WebViewProcessFailure failure = new WebViewProcessFailure(
                            switch (kind) {
                                case 0 -> WebViewProcessFailure.Kind.BROWSER_PROCESS_EXITED;
                                case 1 -> WebViewProcessFailure.Kind.RENDER_PROCESS_EXITED;
                                case 2 -> WebViewProcessFailure.Kind.RENDER_PROCESS_UNRESPONSIVE;
                                default -> WebViewProcessFailure.Kind.UNKNOWN;
                            },
                            "COREWEBVIEW2_PROCESS_FAILED_KIND=" + kind);
                    LOG.log(Level.ERROR, "WebView2 process failure: {0}", failure);
                    for (Consumer<WebViewProcessFailure> listener : failureListeners) {
                        listener.accept(failure);
                    }
                    return Hresult.S_OK;
                });
        addEvent(WebView2.WV_ADD_PROCESS_FAILED, "add_ProcessFailed", processFailedHandler);

        // NewWindowRequested: deny popups by default (spec 12.2)
        MemorySegment newWindowHandler = ComCallback.ptrPtrHandler(registry,
                "NewWindowRequestedHandler", WebView2.IID_NEW_WINDOW_REQUESTED_HANDLER,
                (self, sender, args) -> {
                    ComRuntime.invoke(args, WebView2.NW_ARGS_PUT_HANDLED, THIS_INT, 1);
                    LOG.log(Level.WARNING, "Blocked popup/new-window request");
                    return Hresult.S_OK;
                });
        addEvent(WebView2.WV_ADD_NEW_WINDOW_REQUESTED, "add_NewWindowRequested", newWindowHandler);
    }

    private int handleNavigationStarting(MemorySegment args, boolean mainFrame) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment uriOut = confined.allocate(ADDRESS);
            ComRuntime.invoke(args, WebView2.NAV_ARGS_GET_URI, THIS_PTR, uriOut);
            String uri = WideStrings.readAndFreeCoTaskMem(uriOut.get(ADDRESS, 0));
            MemorySegment userOut = confined.allocate(JAVA_INT);
            ComRuntime.invoke(args, WebView2.NAV_ARGS_GET_IS_USER_INITIATED, THIS_PTR, userOut);
            boolean userInitiated = userOut.get(JAVA_INT, 0) != 0;

            NavigationRequest request;
            try {
                request = new NavigationRequest(URI.create(uri), mainFrame, userInitiated);
            } catch (RuntimeException e) {
                ComRuntime.invoke(args, WebView2.NAV_ARGS_PUT_CANCEL, THIS_INT, 1);
                return Hresult.S_OK;
            }
            for (NavigationListener listener : navigationListeners) {
                if (listener.onNavigate(request) == NavigationDecision.BLOCK) {
                    LOG.log(Level.INFO, "navigationStarting BLOCK main={0} uri={1}", mainFrame, uri);
                    ComRuntime.invoke(args, WebView2.NAV_ARGS_PUT_CANCEL, THIS_INT, 1);
                    return Hresult.S_OK;
                }
            }
            LOG.log(Level.INFO, "navigationStarting ALLOW main={0} uri={1}", mainFrame, uri);
        }
        return Hresult.S_OK;
    }

    private void registerResourceInterception() {
        try (Arena confined = Arena.ofConfined()) {
            ComRuntime.invokeChecked(webView, WebView2.WV_ADD_WEB_RESOURCE_REQUESTED_FILTER,
                    "AddWebResourceRequestedFilter",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
                    WideStrings.alloc(confined, "jdesk://app/*"),
                    WebView2.WEB_RESOURCE_CONTEXT_ALL);
        }
        MemorySegment resourceHandler = ComCallback.ptrPtrHandler(registry,
                "WebResourceRequestedHandler", WebView2.IID_WEB_RESOURCE_REQUESTED_HANDLER,
                (self, sender, args) -> {
                    try {
                        serveResource(args);
                    } catch (RuntimeException e) {
                        LOG.log(Level.ERROR, "Asset interception failed", e);
                    }
                    return Hresult.S_OK;
                });
        addEvent(WebView2.WV_ADD_WEB_RESOURCE_REQUESTED, "add_WebResourceRequested",
                resourceHandler);
    }

    private void serveResource(MemorySegment args) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment requestOut = confined.allocate(ADDRESS);
            ComRuntime.invokeChecked(args, WebView2.WRR_ARGS_GET_REQUEST, "get_Request",
                    THIS_PTR, requestOut);
            MemorySegment request = requestOut.get(ADDRESS, 0);

            MemorySegment uriOut = confined.allocate(ADDRESS);
            ComRuntime.invoke(request, WebView2.WR_REQ_GET_URI, THIS_PTR, uriOut);
            String uri = WideStrings.readAndFreeCoTaskMem(uriOut.get(ADDRESS, 0));
            MemorySegment methodOut = confined.allocate(ADDRESS);
            ComRuntime.invoke(request, WebView2.WR_REQ_GET_METHOD, THIS_PTR, methodOut);
            String method = WideStrings.readAndFreeCoTaskMem(methodOut.get(ADDRESS, 0));
            Map<String, String> requestHeaders = readRangeHeader(request, confined);
            byte[] requestBody = readRequestBody(request, method, confined);
            ComRuntime.release(request);

            AssetResponse asset = app.config().assetHandler()
                    .handle(new AssetRequest(URI.create(uri), method, requestBody, requestHeaders));
            LOG.log(Level.INFO, "asset {0} {1} -> {2}", method, uri, asset.status());

            MemorySegment stream = streamFor(asset);
            StringBuilder headers = new StringBuilder();
            for (Map.Entry<String, String> header : asset.headers().entrySet()) {
                headers.append(header.getKey()).append(": ").append(header.getValue())
                        .append("\r\n");
            }
            MemorySegment responseOut = confined.allocate(ADDRESS);
            ComRuntime.invokeChecked(environment.comPointer(),
                    WebView2.ENV_CREATE_WEB_RESOURCE_RESPONSE, "CreateWebResourceResponse",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS,
                            ADDRESS, ADDRESS),
                    stream,
                    asset.status(),
                    WideStrings.alloc(confined, reasonFor(asset.status())),
                    WideStrings.alloc(confined, headers.toString()),
                    responseOut);
            MemorySegment response = responseOut.get(ADDRESS, 0);
            ComRuntime.invokeChecked(args, WebView2.WRR_ARGS_PUT_RESPONSE, "put_Response",
                    THIS_PTR, response);
            ComRuntime.release(response);
            ComRuntime.release(stream);
        }
    }

    /**
     * Small bodies go through SHCreateMemStream; larger bodies stream through a
     * Java-implemented IStream so assets are never fully buffered (spec 9.1).
     */
    private MemorySegment streamFor(AssetResponse asset) {
        InputStream body = asset.body().get();
        if (asset.contentLength() >= 0 && asset.contentLength() <= 262_144) {
            try (Arena confined = Arena.ofConfined(); body) {
                byte[] bytes = body.readAllBytes();
                MemorySegment nativeBytes = confined.allocate(Math.max(bytes.length, 1));
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, nativeBytes, 0, bytes.length);
                return Win32.shCreateMemStream(nativeBytes, bytes.length);
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Asset body read failed", e);
                return Win32.shCreateMemStream(MemorySegment.NULL, 0);
            }
        }
        return JavaIStream.create(app, body, asset.contentLength());
    }

    /**
     * Reads the Range request header when present. Absence (or any header-API failure)
     * is not an error: the resolver simply serves the full body with 200.
     */
    private static Map<String, String> readRangeHeader(MemorySegment request, Arena confined) {
        try {
            MemorySegment headersOut = confined.allocate(ADDRESS);
            int hr = (int) ComRuntime.invoke(request, WebView2.WR_REQ_GET_HEADERS,
                    THIS_PTR, headersOut);
            MemorySegment headers = headersOut.get(ADDRESS, 0);
            if (hr != Hresult.S_OK || headers.equals(MemorySegment.NULL)) {
                return Map.of();
            }
            try {
                MemorySegment valueOut = confined.allocate(ADDRESS);
                int getHr = (int) ComRuntime.invoke(headers,
                        WebView2.HTTP_REQ_HEADERS_GET_HEADER, THIS_PTR_PTR,
                        WideStrings.alloc(confined, "Range"), valueOut);
                if (getHr != Hresult.S_OK) {
                    return Map.of(); // header not present on this request
                }
                String range = WideStrings.readAndFreeCoTaskMem(valueOut.get(ADDRESS, 0));
                return range == null || range.isEmpty() ? Map.of() : Map.of("Range", range);
            } finally {
                ComRuntime.release(headers);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Reading request headers failed; serving full body", e);
            return Map.of();
        }
    }

    private static final byte[] EMPTY_BODY = new byte[0];
    /** ICoreWebView2WebResourceRequest::get_Content (IStream**), vtable slot 7. */
    private static final int WR_REQ_GET_CONTENT = 7;
    /** ISequentialStream::Read(void* pv, ULONG cb, ULONG* pcbRead) -> HRESULT, vtable slot 3. */
    private static final int ISTREAM_READ = 3;
    private static final FunctionDescriptor ISTREAM_READ_DESC =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

    /**
     * Reads an uploaded request body from the WebView2 request's Content IStream, bounded to
     * the upload cap + 1 so oversize becomes a 413. Empty for GET/HEAD or when there is no
     * body. Compile-verified; runtime-verified on the Windows CI lane.
     */
    private static byte[] readRequestBody(MemorySegment request, String method, Arena confined) {
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)
                && !"PATCH".equalsIgnoreCase(method)) {
            return EMPTY_BODY;
        }
        MemorySegment contentOut = confined.allocate(ADDRESS);
        int hr = (int) ComRuntime.invoke(request, WR_REQ_GET_CONTENT, THIS_PTR, contentOut);
        MemorySegment stream = contentOut.get(ADDRESS, 0);
        if (hr != Hresult.S_OK || stream.equals(MemorySegment.NULL)) {
            return EMPTY_BODY;
        }
        try {
            long limit = dev.jdesk.webview.spi.AssetRequest.MAX_BODY_BYTES + 1;
            int chunkSize = 64 * 1024;
            MemorySegment buffer = confined.allocate(chunkSize);
            MemorySegment readOut = confined.allocate(JAVA_INT);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (out.size() < limit) {
                int want = (int) Math.min(chunkSize, limit - out.size());
                int readHr = (int) ComRuntime.invoke(stream, ISTREAM_READ, ISTREAM_READ_DESC,
                        buffer, want, readOut);
                if (readHr < 0) {
                    break; // stream error
                }
                int read = readOut.get(JAVA_INT, 0);
                if (read <= 0) {
                    break; // end of stream
                }
                out.write(buffer.asSlice(0, read).toArray(JAVA_BYTE), 0, read);
            }
            return out.toByteArray();
        } finally {
            ComRuntime.release(stream);
        }
    }

    private static String reasonFor(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 206 -> "Partial Content";
            case 404 -> "Not Found";
            case 416 -> "Range Not Satisfiable";
            default -> "Internal Error";
        };
    }

    private void addEvent(int slot, String operation, MemorySegment handler) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment token = confined.allocate(JAVA_LONG);
            ComRuntime.invokeChecked(webView, slot, operation, THIS_PTR_PTR, handler, token);
            // Tokens are not individually removed: the controller is closed as a whole
            // and the callback gate rejects any straggler upcalls (spec 6.2).
        }
    }

    private URI currentSource() {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment out = confined.allocate(ADDRESS);
            // ICoreWebView2::get_Source, slot 4 per the generated reference.
            ComRuntime.invoke(webView, 4, THIS_PTR, out);
            String source = WideStrings.readAndFreeCoTaskMem(out.get(ADDRESS, 0));
            return URI.create(source.isEmpty() ? "about:blank" : source);
        } catch (RuntimeException e) {
            return URI.create("about:blank");
        }
    }

    void resizeToClientArea() {
        if (closed || controller == null) {
            return;
        }
        WindowBounds bounds = window.clientBounds();
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment rect = confined.allocate(Win32.RECT);
            rect.set(JAVA_INT, 0, bounds.x());
            rect.set(JAVA_INT, 4, bounds.y());
            rect.set(JAVA_INT, 8, bounds.width());
            rect.set(JAVA_INT, 12, bounds.height());
            // put_Bounds takes RECT by value: x64 passes structs > 8 bytes by pointer,
            // which the linker models as a struct layout argument.
            ComRuntime.invoke(controller, WebView2.CTRL_PUT_BOUNDS,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, Win32.RECT), rect);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "put_Bounds failed", e);
        }
    }

    // ---- PlatformWebView ----

    @Override
    public void navigate(URI uri) {
        try (Arena confined = Arena.ofConfined()) {
            ComRuntime.invokeChecked(webView, WebView2.WV_NAVIGATE, "Navigate", THIS_PTR,
                    WideStrings.alloc(confined, uri.toString()));
        }
    }

    @Override
    public void postJson(String json) {
        if (closed) {
            return;
        }
        LOG.log(Level.INFO, "bridge-> {0} chars", json.length());
        try (Arena confined = Arena.ofConfined()) {
            ComRuntime.invokeChecked(webView, WebView2.WV_POST_WEB_MESSAGE_AS_STRING,
                    "PostWebMessageAsString", THIS_PTR, WideStrings.alloc(confined, json));
        }
    }

    @Override
    public CompletionStage<String> evaluate(String script) {
        CompletableFuture<String> future = new CompletableFuture<>();
        MemorySegment handler = ComCallback.hrPtrHandler(registry,
                "ExecuteScriptCompletedHandler", WebView2.IID_EXECUTE_SCRIPT_COMPLETED_HANDLER,
                (self, hr, resultJson) -> {
                    if (hr < 0) {
                        future.completeExceptionally(new Hresult.ComException("ExecuteScript", hr));
                    } else {
                        // WebView2 ExecuteScript returns the result JSON-encoded, so a JS
                        // string comes back quoted ("x"). WKWebView and WebKitGTK return the
                        // raw string. Unwrap a top-level JSON string so evaluate() is
                        // consistent across platforms. (callee-owned result: no free.)
                        future.complete(unwrapJsonString(WideStrings.read(resultJson)));
                    }
                    return Hresult.S_OK;
                });
        try (Arena confined = Arena.ofConfined()) {
            ComRuntime.invokeChecked(webView, WebView2.WV_EXECUTE_SCRIPT, "ExecuteScript",
                    THIS_PTR_PTR, WideStrings.alloc(confined, script), handler);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Decodes a top-level JSON string literal to its raw value (e.g. {@code "left"} to
     * {@code left}), so evaluate() matches the raw-string result of WKWebView/WebKitGTK.
     * Non-string JSON (numbers, objects, {@code null}) is returned unchanged.
     */
    static String unwrapJsonString(String json) {
        if (json == null || json.length() < 2
                || json.charAt(0) != '"' || json.charAt(json.length() - 1) != '"') {
            return json;
        }
        StringBuilder out = new StringBuilder(json.length() - 2);
        for (int i = 1; i < json.length() - 1; i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length() - 1) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '/' -> out.append('/');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        if (i + 4 < json.length() - 1) {
                            out.append((char) Integer.parseInt(
                                    json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
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

    @Override
    public CompletionStage<WebViewSnapshot> snapshot() {
        CompletableFuture<WebViewSnapshot> future = new CompletableFuture<>();
        Arena streamArena = Arena.ofShared();
        MemorySegment streamOut = streamArena.allocate(ADDRESS);
        int hr = Win32.createStreamOnHGlobal(MemorySegment.NULL, true, streamOut);
        if (hr < 0) {
            streamArena.close();
            future.completeExceptionally(new Hresult.ComException("CreateStreamOnHGlobal", hr));
            return future;
        }
        MemorySegment stream = streamOut.get(ADDRESS, 0);
        WindowBounds bounds = window.clientBounds();
        MemorySegment handler = ComCallback.hrHandler(registry,
                "CapturePreviewCompletedHandler", WebView2.IID_CAPTURE_PREVIEW_COMPLETED_HANDLER,
                (self, resultHr) -> {
                    try {
                        if (resultHr < 0) {
                            future.completeExceptionally(
                                    new Hresult.ComException("CapturePreview", resultHr));
                        } else {
                            byte[] png = readStreamBytes(stream);
                            future.complete(new WebViewSnapshot(
                                    Math.max(bounds.width(), 1), Math.max(bounds.height(), 1), png));
                        }
                    } catch (RuntimeException e) {
                        future.completeExceptionally(e);
                    } finally {
                        ComRuntime.release(stream);
                        streamArena.close();
                    }
                    return Hresult.S_OK;
                });
        try {
            // COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_PNG = 0
            ComRuntime.invokeChecked(webView, WebView2.WV_CAPTURE_PREVIEW, "CapturePreview",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    0, stream, handler);
        } catch (RuntimeException e) {
            ComRuntime.release(stream);
            streamArena.close();
            future.completeExceptionally(e);
        }
        return future;
    }

    private static byte[] readStreamBytes(MemorySegment stream) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment hGlobalOut = confined.allocate(ADDRESS);
            Hresult.check(Win32.getHGlobalFromStream(stream, hGlobalOut), "GetHGlobalFromStream");
            MemorySegment hGlobal = hGlobalOut.get(ADDRESS, 0);
            long size = Win32.globalSize(hGlobal);
            MemorySegment data = Win32.globalLock(hGlobal);
            try {
                byte[] bytes = new byte[(int) size];
                MemorySegment.copy(data.reinterpret(size), 0,
                        MemorySegment.ofArray(bytes), 0, size);
                return bytes;
            } finally {
                Win32.globalUnlock(hGlobal);
            }
        }
    }

    @Override
    public WebViewDiagnostics diagnostics() {
        Long pid = null;
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment out = confined.allocate(JAVA_INT);
            ComRuntime.invoke(webView, WebView2.WV_GET_BROWSER_PROCESS_ID, THIS_PTR, out);
            pid = (long) out.get(JAVA_INT, 0);
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "get_BrowserProcessId failed", e);
        }
        return new WebViewDiagnostics(
                java.util.Optional.of("WebView2 " + environment.browserVersion()),
                java.util.Optional.empty(),
                java.util.Optional.ofNullable(pid));
    }

    @Override
    public CompletionStage<Void> clearData(Set<WebViewDataType> dataTypes) {
        int kinds = 0;
        if (dataTypes.contains(WebViewDataType.COOKIES)) {
            kinds |= WebView2.BROWSING_DATA_COOKIES;
        }
        if (dataTypes.contains(WebViewDataType.CACHE)) {
            kinds |= WebView2.BROWSING_DATA_DISK_CACHE;
        }
        if (dataTypes.contains(WebViewDataType.LOCAL_STORAGE)) {
            kinds |= WebView2.BROWSING_DATA_LOCAL_STORAGE;
        }
        int nativeKinds = kinds;
        if (dataTypes.contains(WebViewDataType.LOCAL_STORAGE)) {
            // ClearBrowsingData does not remove localStorage for WebView2 custom schemes,
            // including jdesk://. Clearing it in one loaded document updates the backing
            // store shared by every window in this session; the profile API below still
            // handles standard origins and all other selected data kinds.
            return evaluate("(() => { try { localStorage.clear(); return true; } "
                    + "catch (_) { return false; } })()")
                    .handle((ignored, evaluateError) -> clearProfileData(nativeKinds))
                    .thenCompose(stage -> stage);
        }
        return clearProfileData(nativeKinds);
    }

    private CompletionStage<Void> clearProfileData(int kinds) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        MemorySegment completion = ComCallback.hrHandler(registry,
                "ClearBrowsingDataCompletedHandler",
                WebView2.IID_CLEAR_BROWSING_DATA_COMPLETED_HANDLER,
                (self, hr) -> {
                    if (hr < 0) {
                        future.completeExceptionally(
                                new Hresult.ComException("ClearBrowsingData", hr));
                    } else {
                        future.complete(null);
                    }
                    return Hresult.S_OK;
                });
        MemorySegment webView13 = MemorySegment.NULL;
        MemorySegment profile = MemorySegment.NULL;
        MemorySegment profile2 = MemorySegment.NULL;
        try (Arena confined = Arena.ofConfined()) {
            webView13 = ComRuntime.queryInterface(webView, WebView2.IID_WEBVIEW2_13);
            MemorySegment out = confined.allocate(ADDRESS);
            ComRuntime.invokeChecked(webView13, WebView2.WV13_GET_PROFILE,
                    "get_Profile", THIS_PTR, out);
            profile = out.get(ADDRESS, 0);
            profile2 = ComRuntime.queryInterface(profile, WebView2.IID_PROFILE2);
            ComRuntime.invokeChecked(profile2, WebView2.PROFILE2_CLEAR_BROWSING_DATA,
                    "ClearBrowsingData",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                    kinds, completion);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        } finally {
            if (!profile2.equals(MemorySegment.NULL)) {
                ComRuntime.release(profile2);
            }
            if (!profile.equals(MemorySegment.NULL)) {
                ComRuntime.release(profile);
            }
            if (!webView13.equals(MemorySegment.NULL)) {
                ComRuntime.release(webView13);
            }
        }
        return future;
    }
    @Override public boolean devToolsEnabled(){return devToolsEnabled;}

    /** Subscribes to engine process failures (spec section 13). */
    @Override
    public Subscription onProcessFailure(Consumer<WebViewProcessFailure> listener) {
        failureListeners.add(listener);
        return () -> failureListeners.remove(listener);
    }

    /** Called from the window's WM_DESTROY path; closes the controller pipeline. */
    void destroyFromWindow() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ComRuntime.release(webView);
            ComRuntime.invoke(controller, WebView2.CTRL_CLOSE,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            ComRuntime.release(controller);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "WebView2 teardown failed", e);
        }
    }

    @Override
    public void close() {
        // The window owns the teardown ordering; closing the WebView alone closes it too.
        destroyFromWindow();
    }
}
