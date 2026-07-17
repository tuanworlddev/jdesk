package dev.jdesk.platform.linux;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.WebViewSessionConfig;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.ffm.NativeHandle;
import dev.jdesk.webview.spi.AssetRequest;
import dev.jdesk.webview.spi.AssetResponse;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformWindow;
import java.io.ByteArrayOutputStream;
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
import dev.jdesk.api.MessageDialog;
import dev.jdesk.api.MessageDialogResult;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * One GTK application. The UI thread is the thread that constructs this application
 * (it runs {@code gtk_init_check}) and later blocks in {@code gtk_main} (spec section
 * 7: GTK and WebKitGTK run on their GLib main context). The {@code jdesk} URI scheme
 * is registered on the default {@code WebKitWebContext} before the first
 * {@code WebKitWebView} exists, and is classified secure + CORS-enabled through the
 * public {@code WebKitSecurityManager} API only (spec ADR-004 / section 9.1).
 */
final class LinuxPlatformApplication extends NativeHandle implements PlatformApplication {
    private static final Logger LOG = System.getLogger(LinuxPlatformApplication.class.getName());

    // void (*WebKitURISchemeRequestCallback)(WebKitURISchemeRequest*, gpointer)
    private static final FunctionDescriptor SCHEME_CALLBACK_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /** The application currently serving {@code jdesk://} requests. */
    private static final AtomicReference<LinuxPlatformApplication> SCHEME_TARGET =
            new AtomicReference<>();
    private static final Object DEFAULT_SCHEME_LOCK = new Object();
    /** The process-wide default context accepts one registration per scheme. */
    private static boolean defaultSchemeRegistered;

    private final PlatformApplicationConfig config;
    private final LinuxUiDispatcher dispatcher;
    /** Pins app-lifetime callback state (scheme routing); owner of the app gate. */
    private final NativeCallbackRegistry registry;
    private volatile boolean stopRequested;
    private volatile boolean loopRunning;
    private volatile LinuxPtyBackend ptyBackend;
    /** Session id -> immutable config and owned WebKitWebContext. UI-thread only. */
    private final Map<String, SessionContext> webContexts = new HashMap<>();

    private record SessionContext(WebViewSessionConfig config, MemorySegment context) {
    }

    LinuxPlatformApplication(PlatformApplicationConfig config) {
        super("LinuxPlatformApplication");
        this.config = config;
        int initialized;
        try {
            initialized = (int) Gtk.GTK_INIT_CHECK.invokeExact(
                    MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
        if (initialized == 0) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "gtk_init_check failed: no display available (DISPLAY/WAYLAND_DISPLAY "
                            + "unset or unreachable). On a headless machine run under Xvfb, "
                            + "e.g. xvfb-run -a <command>.");
        }
        this.dispatcher = new LinuxUiDispatcher(config.devMode());
        this.registry = new NativeCallbackRegistry("linux-app", Arena.ofShared());
        LinuxPlatformApplication previous = SCHEME_TARGET.getAndSet(this);
        if (previous != null) {
            LOG.log(Level.WARNING,
                    "Replacing previous scheme target (multiple applications in-process)");
        }
        registry.register(new NativeCallbackRegistry.Registration(
                "jdesk-scheme-target", this, MethodHandles.constant(Object.class, null),
                schemeCallbackStub(), null,
                () -> SCHEME_TARGET.compareAndSet(this, null)));
        LOG.log(Level.INFO, "GTK initialized; WebKitGTK {0}", webKitVersion());
        markOpen();
    }

    // ---- jdesk:// scheme (spec section 9; WebKitGTK custom URI scheme, public APIs) ----

    private static volatile MemorySegment schemeStub;

    private static MemorySegment schemeCallbackStub() {
        MemorySegment stub = schemeStub;
        if (stub == null) {
            try {
                stub = Gtk.upcall(MethodHandles.lookup().findStatic(
                                LinuxPlatformApplication.class, "onSchemeRequest",
                                MethodType.methodType(void.class, MemorySegment.class,
                                        MemorySegment.class)),
                        SCHEME_CALLBACK_DESC);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            schemeStub = stub;
        }
        return stub;
    }

    private static void registerJdeskScheme(MemorySegment context) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment scheme = confined.allocateFrom("jdesk");
            Gtk.WEBKIT_WEB_CONTEXT_REGISTER_URI_SCHEME.invokeExact(context, scheme,
                    schemeCallbackStub(), MemorySegment.NULL, MemorySegment.NULL);
            MemorySegment securityManager = (MemorySegment)
                    Gtk.WEBKIT_WEB_CONTEXT_GET_SECURITY_MANAGER.invokeExact(context);
            Gtk.WEBKIT_SECURITY_MANAGER_REGISTER_URI_SCHEME_AS_SECURE.invokeExact(
                    securityManager, scheme);
            Gtk.WEBKIT_SECURITY_MANAGER_REGISTER_URI_SCHEME_AS_CORS_ENABLED.invokeExact(
                    securityManager, scheme);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    /** Returns an owned application-lifetime context for an isolated browser session. */
    MemorySegment webContext(WebViewSessionConfig session) {
        SessionContext existing = webContexts.get(session.id());
        if (existing != null) {
            if (!existing.config().equals(session)) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "WebView session '" + session.id()
                                + "' was already opened with different settings");
            }
            return existing.context();
        }
        MemorySegment context = createWebContext(session);
        if (context.equals(MemorySegment.NULL)) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "WebKit returned no context for session '" + session.id() + "'");
        }
        try {
            if (session.storage() == WebViewSessionConfig.Storage.PERSISTENT) {
                registerDefaultJdeskScheme(context);
            } else {
                registerJdeskScheme(context);
            }
        } catch (RuntimeException e) {
            Gtk.gObjectUnref(context);
            throw e;
        }
        webContexts.put(session.id(), new SessionContext(session, context));
        LOG.log(Level.INFO, "Created {0} WebView session {1}",
                session.storage(), session.id());
        return context;
    }

    private MemorySegment createWebContext(WebViewSessionConfig session) {
        try {
            if (session.storage() == WebViewSessionConfig.Storage.PRIVATE) {
                return (MemorySegment) Gtk.WEBKIT_WEB_CONTEXT_NEW_EPHEMERAL.invokeExact();
            }
            if (!session.id().equals(WebViewSessionConfig.DEFAULT.id())) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Named persistent WebView sessions are not supported on Linux; "
                                + "use the 'default' session or a private session");
            }
            MemorySegment defaultContext = (MemorySegment)
                    Gtk.WEBKIT_WEB_CONTEXT_GET_DEFAULT.invokeExact();
            return Gtk.gObjectRef(defaultContext);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Cannot create isolated WebKit session '" + session.id() + "'", t);
        }
    }

    private static void registerDefaultJdeskScheme(MemorySegment context) {
        synchronized (DEFAULT_SCHEME_LOCK) {
            if (!defaultSchemeRegistered) {
                registerJdeskScheme(context);
                defaultSchemeRegistered = true;
            }
        }
    }

    @SuppressWarnings("unused") // WebKitURISchemeRequestCallback upcall (GTK main thread)
    static void onSchemeRequest(MemorySegment request, MemorySegment userData) {
        LinuxPlatformApplication app = SCHEME_TARGET.get();
        if (app == null || !app.registry.gate().enter()) {
            failRequestQuietly(request, "application closed");
            return;
        }
        try {
            app.serveSchemeRequest(request);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Asset interception failed", t);
            failRequestQuietly(request, "asset interception failed");
        } finally {
            app.registry.gate().exit();
        }
    }

    /**
     * Serves one {@code jdesk://} request synchronously on the GTK main thread through
     * the runtime {@link dev.jdesk.webview.spi.AssetHandler} — no sockets anywhere.
     * Status, Content-Type, and all runtime-supplied headers (CSP included) go out via
     * {@code WebKitURISchemeResponse} + {@code SoupMessageHeaders}.
     */
    private void serveSchemeRequest(MemorySegment request) {
        String url;
        String method;
        try {
            url = Gtk.javaString((MemorySegment)
                    Gtk.WEBKIT_URI_SCHEME_REQUEST_GET_URI.invokeExact(request));
            MemorySegment methodPtr = (MemorySegment)
                    Gtk.WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_METHOD.invokeExact(request);
            method = Gtk.javaString(methodPtr);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
        if (method == null || method.isEmpty()) {
            method = "GET";
        }
        Map<String, String> requestHeaders = readRangeHeader(request);
        byte[] requestBody = readRequestBody(request, method);
        AssetResponse asset;
        try {
            asset = config.assetHandler().handle(
                    new AssetRequest(URI.create(url), method, requestBody, requestHeaders));
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Asset handler failed for {0}", url, e);
            failRequestQuietly(request, "asset resolution failed");
            return;
        }
        LOG.log(Level.INFO, "asset {0} {1} -> {2}", method, url, asset.status());

        byte[] body;
        try (InputStream stream = asset.body().get()) {
            // WebKitURISchemeResponse pulls from one GInputStream; the body is
            // materialized into a GBytes-backed memory stream (assets are bounded by
            // the runtime resolver; documented v1 limitation vs. true streaming).
            body = readFully(stream);
        } catch (IOException e) {
            LOG.log(Level.ERROR, "Asset body read failed for {0}", url, e);
            failRequestQuietly(request, "asset body read failed");
            return;
        }

        try (Arena confined = Arena.ofConfined()) {
            MemorySegment nativeBody = body.length == 0
                    ? MemorySegment.NULL : confined.allocateFrom(
                            java.lang.foreign.ValueLayout.JAVA_BYTE, body);
            MemorySegment bytes = (MemorySegment) Gtk.G_BYTES_NEW.invokeExact(
                    nativeBody, (long) body.length);
            MemorySegment stream = (MemorySegment)
                    Gtk.G_MEMORY_INPUT_STREAM_NEW_FROM_BYTES.invokeExact(bytes);
            Gtk.G_BYTES_UNREF.invokeExact(bytes); // the stream holds its own reference

            MemorySegment response = (MemorySegment) Gtk.WEBKIT_URI_SCHEME_RESPONSE_NEW
                    .invokeExact(stream, (long) body.length);
            try {
                Gtk.WEBKIT_URI_SCHEME_RESPONSE_SET_STATUS.invokeExact(response,
                        asset.status(), MemorySegment.NULL);
                String contentType = asset.headers().getOrDefault("Content-Type",
                        "application/octet-stream");
                Gtk.WEBKIT_URI_SCHEME_RESPONSE_SET_CONTENT_TYPE.invokeExact(response,
                        confined.allocateFrom(contentType));
                MemorySegment headers = (MemorySegment) Gtk.SOUP_MESSAGE_HEADERS_NEW
                        .invokeExact(Gtk.SOUP_MESSAGE_HEADERS_RESPONSE);
                for (Map.Entry<String, String> header : asset.headers().entrySet()) {
                    Gtk.SOUP_MESSAGE_HEADERS_APPEND.invokeExact(headers,
                            confined.allocateFrom(header.getKey()),
                            confined.allocateFrom(header.getValue()));
                }
                // transfer full: the response owns the SoupMessageHeaders from here.
                Gtk.WEBKIT_URI_SCHEME_RESPONSE_SET_HTTP_HEADERS.invokeExact(response, headers);
                Gtk.WEBKIT_URI_SCHEME_REQUEST_FINISH_WITH_RESPONSE.invokeExact(
                        request, response);
            } finally {
                Gtk.gObjectUnref(response);
                Gtk.gObjectUnref(stream);
            }
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    /**
     * Reads the Range request header when present (needed for 206 media seeking).
     * Absence or any header-API failure is not an error: the resolver serves 200.
     */
    private static Map<String, String> readRangeHeader(MemorySegment request) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment soupHeaders = (MemorySegment)
                    Gtk.WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_HEADERS.invokeExact(request);
            if (soupHeaders.equals(MemorySegment.NULL)) {
                return Map.of();
            }
            MemorySegment value = (MemorySegment) Gtk.SOUP_MESSAGE_HEADERS_GET_ONE
                    .invokeExact(soupHeaders, confined.allocateFrom("Range"));
            String range = Gtk.javaString(value);
            return range == null || range.isEmpty() ? Map.of() : Map.of("Range", range);
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "Reading request headers failed; serving full body", t);
            return Map.of();
        }
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        stream.transferTo(buffer);
        return buffer.toByteArray();
    }

    private static final byte[] EMPTY_BODY = new byte[0];

    /**
     * Reads an uploaded request body via {@code webkit_uri_scheme_request_get_http_body}
     * (WebKitGTK 2.36+), bounded to the upload cap + 1 so oversize becomes a 413. Returns
     * empty when the symbol is unavailable (older WebKitGTK) — see PLATFORM-001.
     */
    private static byte[] readRequestBody(MemorySegment request, String method) {
        if (Gtk.WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_BODY == null) {
            return EMPTY_BODY;
        }
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)
                && !"PATCH".equalsIgnoreCase(method)) {
            return EMPTY_BODY;
        }
        MemorySegment stream;
        try {
            stream = (MemorySegment) Gtk.WEBKIT_URI_SCHEME_REQUEST_GET_HTTP_BODY
                    .invokeExact(request);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
        if (stream == null || stream.equals(MemorySegment.NULL)) {
            return EMPTY_BODY;
        }
        long limit = dev.jdesk.webview.spi.AssetRequest.MAX_BODY_BYTES + 1;
        try {
            return readGInputStream(stream, limit);
        } finally {
            try {
                Gtk.G_OBJECT_UNREF.invokeExact(stream); // get_http_body is transfer-full
            } catch (Throwable ignored) {
                // defensive
            }
        }
    }

    private static byte[] readGInputStream(MemorySegment stream, long limit) {
        try (Arena arena = Arena.ofConfined()) {
            int chunkSize = 64 * 1024;
            MemorySegment buffer = arena.allocate(chunkSize);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (out.size() < limit) {
                long want = Math.min(chunkSize, limit - out.size());
                long n = (long) Gtk.G_INPUT_STREAM_READ.invokeExact(
                        stream, buffer, want, MemorySegment.NULL, MemorySegment.NULL);
                if (n <= 0) {
                    break; // 0 = EOF, -1 = error
                }
                out.write(buffer.asSlice(0, n).toArray(JAVA_BYTE), 0, (int) n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    private static void failRequestQuietly(MemorySegment request, String message) {
        try (Arena confined = Arena.ofConfined()) {
            int domain = (int) Gtk.G_QUARK_FROM_STRING.invokeExact(
                    confined.allocateFrom("dev.jdesk"));
            MemorySegment error = (MemorySegment) Gtk.G_ERROR_NEW_LITERAL.invokeExact(
                    domain, 1, confined.allocateFrom(message));
            Gtk.WEBKIT_URI_SCHEME_REQUEST_FINISH_ERROR.invokeExact(request, error);
            Gtk.G_ERROR_FREE.invokeExact(error);
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "finish_error failed after: {0}", message);
        }
    }

    // ---- PlatformApplication ----

    @Override
    public UiDispatcher ui() {
        return dispatcher;
    }

    LinuxUiDispatcher dispatcher() {
        return dispatcher;
    }

    PlatformApplicationConfig config() {
        return config;
    }

    @Override
    public PlatformWindow createWindow(NativeWindowConfig windowConfig) {
        requireOpen();
        dispatcher.assertUiThread();
        return new LinuxWindow(this, windowConfig);
    }

    @Override
    public java.util.Optional<dev.jdesk.webview.spi.PtyBackend> ptyBackend() {
        LinuxPtyBackend backend = ptyBackend;
        if (backend == null) {
            synchronized (this) {
                backend = ptyBackend;
                if (backend == null) {
                    backend = new LinuxPtyBackend();
                    ptyBackend = backend;
                }
            }
        }
        return java.util.Optional.of(backend);
    }

    @Override public dev.jdesk.api.SystemTheme systemTheme() {
        requireOpen(); dispatcher.assertUiThread();
        return LinuxDesktop.systemTheme();
    }
    @Override public void showNotification(String title, String body) {
        requireOpen(); dispatcher.assertUiThread();
        LinuxDesktop.showNotification(title, body);
    }
    @Override public void setApplicationIcon(byte[] pngData) {
        requireOpen(); dispatcher.assertUiThread();
        LinuxDesktop.setApplicationIcon(pngData);
    }
    @Override public void setDockBadge(String label) {
        requireOpen(); dispatcher.assertUiThread();
        LinuxDesktop.setDockBadge(label);
    }
    @Override public dev.jdesk.webview.spi.TrayControl createTrayItem(
            dev.jdesk.api.TraySpec spec, java.util.function.Consumer<String> onAction) {
        requireOpen(); dispatcher.assertUiThread();
        return LinuxDesktop.createTrayItem(spec, onAction);
    }

    @Override public void openExternal(URI uri) {
        requireOpen(); dispatcher.assertUiThread();
        try (Arena arena = Arena.ofConfined()) {
            int ok = (int) Gtk.G_APP_INFO_LAUNCH_DEFAULT_FOR_URI.invokeExact(
                    arena.allocateFrom(uri.toString()), MemorySegment.NULL, MemorySegment.NULL);
            if (ok == 0) throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "OS refused external URI");
        } catch (Throwable t) { throw Gtk.rethrow(t); }
    }
    private MemorySegment clipboard() throws Throwable {
        try(Arena arena=Arena.ofConfined()){
            MemorySegment atom=(MemorySegment)Gtk.GDK_ATOM_INTERN_STATIC_STRING.invokeExact(arena.allocateFrom("CLIPBOARD"));
            return (MemorySegment)Gtk.GTK_CLIPBOARD_GET.invokeExact(atom);
        }
    }
    @Override public dev.jdesk.api.SecretStore secrets(String applicationId) {
        // secret-tool subprocess is thread-safe; no UI-thread assertion by design.
        return new LinuxSecretStore(applicationId);
    }
    @Override public String readClipboardText(){requireOpen();dispatcher.assertUiThread();try{
        MemorySegment text=(MemorySegment)Gtk.GTK_CLIPBOARD_WAIT_FOR_TEXT.invokeExact(clipboard());
        if(text.equals(MemorySegment.NULL))return "";return Gtk.takeString(text);
    }catch(Throwable t){throw Gtk.rethrow(t);}}
    @Override public void writeClipboardText(String text){requireOpen();dispatcher.assertUiThread();try(Arena arena=Arena.ofConfined()){
        MemorySegment cb=clipboard();Gtk.GTK_CLIPBOARD_SET_TEXT.invokeExact(cb,arena.allocateFrom(text),-1);Gtk.GTK_CLIPBOARD_STORE.invokeExact(cb);
    }catch(Throwable t){throw Gtk.rethrow(t);}}
    @Override public byte[] readClipboard(String type){requireOpen();dispatcher.assertUiThread();try{
        return LinuxClipboard.read(clipboard(), type);
    }catch(JDeskException e){throw e;}catch(Throwable t){throw Gtk.rethrow(t);}}
    @Override public void writeClipboard(String type, byte[] data){requireOpen();dispatcher.assertUiThread();try{
        LinuxClipboard.write(clipboard(), type, data);
    }catch(JDeskException e){throw e;}catch(Throwable t){throw Gtk.rethrow(t);}}
    @Override public Runnable registerGlobalShortcut(String accelerator, Runnable callback){
        requireOpen(); dispatcher.assertUiThread();
        return LinuxGlobalShortcut.register(accelerator, callback);
    }
    @Override public MessageDialogResult showMessageDialog(MessageDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        try (Arena arena = Arena.ofConfined()) {
            int type = switch (dialog.kind()) { case INFO -> 0; case WARNING -> 1; case ERROR -> 3; };
            MemorySegment nativeDialog = (MemorySegment) Gtk.GTK_MESSAGE_DIALOG_NEW.invokeExact(
                    MemorySegment.NULL, 0, type, 0, arena.allocateFrom("%s"),
                    arena.allocateFrom(dialog.message()));
            Gtk.GTK_WINDOW_SET_TITLE_DIALOG.invokeExact(nativeDialog, arena.allocateFrom(dialog.title()));
            for (int i = 0; i < dialog.buttons().size(); i++)
                Gtk.GTK_DIALOG_ADD_BUTTON.invokeExact(nativeDialog,
                        arena.allocateFrom(dialog.buttons().get(i)), 1000 + i);
            int response = (int) Gtk.GTK_DIALOG_RUN.invokeExact(nativeDialog);
            Gtk.GTK_WIDGET_DESTROY.invokeExact(nativeDialog);
            int index = response - 1000;
            if (index < 0 || index >= dialog.buttons().size()) index = 0;
            return new MessageDialogResult(index, dialog.buttons().get(index));
        } catch (Throwable t) { throw Gtk.rethrow(t); }
    }

    private static final int GTK_FILE_CHOOSER_ACTION_OPEN = 0;
    private static final int GTK_FILE_CHOOSER_ACTION_SAVE = 1;
    private static final int GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER = 2;
    private static final int GTK_RESPONSE_ACCEPT = -3;

    @Override public dev.jdesk.api.FileDialogResult showOpenDialog(
            dev.jdesk.api.FileDialog.OpenDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        int action = dialog.chooseDirectories()
                ? GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER : GTK_FILE_CHOOSER_ACTION_OPEN;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment chooser = newChooser(arena, dialog.title(), action);
            try {
                if (dialog.allowMultiple()) {
                    Gtk.GTK_FILE_CHOOSER_SET_SELECT_MULTIPLE.invokeExact(chooser, 1);
                }
                dialog.directory().ifPresent(dir -> setFolder(arena, chooser, dir));
                if ((int) Gtk.GTK_DIALOG_RUN.invokeExact(chooser) != GTK_RESPONSE_ACCEPT) {
                    return dev.jdesk.api.FileDialogResult.cancelled();
                }
                return new dev.jdesk.api.FileDialogResult(
                        dialog.allowMultiple() ? readFilenames(chooser) : readFilename(chooser));
            } finally {
                Gtk.GTK_WIDGET_DESTROY.invokeExact(chooser);
            }
        } catch (Throwable t) { throw Gtk.rethrow(t); }
    }

    @Override public dev.jdesk.api.FileDialogResult showSaveDialog(
            dev.jdesk.api.FileDialog.SaveDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment chooser = newChooser(arena, dialog.title(), GTK_FILE_CHOOSER_ACTION_SAVE);
            try {
                Gtk.GTK_FILE_CHOOSER_SET_DO_OVERWRITE_CONFIRMATION.invokeExact(chooser, 1);
                dialog.directory().ifPresent(dir -> setFolder(arena, chooser, dir));
                dialog.suggestedName().ifPresent(name -> {
                    try {
                        Gtk.GTK_FILE_CHOOSER_SET_CURRENT_NAME.invokeExact(chooser,
                                arena.allocateFrom(name));
                    } catch (Throwable t) { throw Gtk.rethrow(t); }
                });
                if ((int) Gtk.GTK_DIALOG_RUN.invokeExact(chooser) != GTK_RESPONSE_ACCEPT) {
                    return dev.jdesk.api.FileDialogResult.cancelled();
                }
                java.util.List<String> paths = readFilename(chooser);
                return paths.isEmpty()
                        ? dev.jdesk.api.FileDialogResult.cancelled()
                        : new dev.jdesk.api.FileDialogResult(paths);
            } finally {
                Gtk.GTK_WIDGET_DESTROY.invokeExact(chooser);
            }
        } catch (Throwable t) { throw Gtk.rethrow(t); }
    }

    @Override public void printFile(dev.jdesk.api.PrintJob job) {
        dev.jdesk.webview.spi.CupsPrinting.printFile(job); // CUPS lp, off the UI thread
    }

    private MemorySegment newChooser(Arena arena, String title, int action) throws Throwable {
        String accept = action == GTK_FILE_CHOOSER_ACTION_SAVE ? "_Save"
                : action == GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER ? "_Select" : "_Open";
        return (MemorySegment) Gtk.GTK_FILE_CHOOSER_DIALOG_NEW.invokeExact(
                arena.allocateFrom(title.isEmpty() ? " " : title), MemorySegment.NULL, action,
                arena.allocateFrom("_Cancel"), -6 /* GTK_RESPONSE_CANCEL */,
                arena.allocateFrom(accept), GTK_RESPONSE_ACCEPT);
    }

    private void setFolder(Arena arena, MemorySegment chooser, String dir) {
        try {
            int ignored = (int) Gtk.GTK_FILE_CHOOSER_SET_CURRENT_FOLDER.invokeExact(
                    chooser, arena.allocateFrom(dir));
        } catch (Throwable t) { throw Gtk.rethrow(t); }
    }

    private java.util.List<String> readFilename(MemorySegment chooser) throws Throwable {
        MemorySegment cstr = (MemorySegment) Gtk.GTK_FILE_CHOOSER_GET_FILENAME.invokeExact(chooser);
        if (cstr.equals(MemorySegment.NULL)) {
            return java.util.List.of();
        }
        String path = Gtk.javaString(cstr);
        Gtk.G_FREE.invokeExact(cstr);
        return path == null || path.isEmpty() ? java.util.List.of() : java.util.List.of(path);
    }

    private java.util.List<String> readFilenames(MemorySegment chooser) throws Throwable {
        MemorySegment list = (MemorySegment) Gtk.GTK_FILE_CHOOSER_GET_FILENAMES.invokeExact(chooser);
        if (list.equals(MemorySegment.NULL)) {
            return java.util.List.of();
        }
        java.util.List<String> paths = new java.util.ArrayList<>();
        int count = (int) Gtk.G_SLIST_LENGTH.invokeExact(list);
        for (int i = 0; i < count; i++) {
            MemorySegment data = (MemorySegment) Gtk.G_SLIST_NTH_DATA.invokeExact(list, i);
            if (!data.equals(MemorySegment.NULL)) {
                String path = Gtk.javaString(data);
                if (path != null && !path.isEmpty()) {
                    paths.add(path);
                }
                Gtk.G_FREE.invokeExact(data);
            }
        }
        Gtk.G_SLIST_FREE.invokeExact(list);
        return paths;
    }

    @Override
    public void runEventLoop() {
        requireOpen();
        dispatcher.assertUiThread();
        if (stopRequested) {
            return;
        }
        loopRunning = true;
        try {
            Gtk.GTK_MAIN.invokeExact();
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        } finally {
            loopRunning = false;
        }
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        dispatcher.execute(() -> {
            if (!loopRunning) {
                return; // gtk_main not running (never started or already quit)
            }
            try {
                Gtk.GTK_MAIN_QUIT.invokeExact();
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "gtk_main_quit failed", Gtk.rethrow(t));
            }
        });
    }

    static String webKitVersion() {
        try {
            int major = (int) Gtk.WEBKIT_GET_MAJOR_VERSION.invokeExact();
            int minor = (int) Gtk.WEBKIT_GET_MINOR_VERSION.invokeExact();
            int micro = (int) Gtk.WEBKIT_GET_MICRO_VERSION.invokeExact();
            return major + "." + minor + "." + micro;
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override
    protected void releaseNative() {
        dispatcher.assertUiThread();
        // Detaches the scheme routing target and closes the app gate. Each session context
        // owns its scheme registration and is released below; requests racing teardown see
        // no target and fail deterministically.
        registry.close();
        for (SessionContext session : webContexts.values()) {
            Gtk.gObjectUnref(session.context());
        }
        webContexts.clear();
    }
}
