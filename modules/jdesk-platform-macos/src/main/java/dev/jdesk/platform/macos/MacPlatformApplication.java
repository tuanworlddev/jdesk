package dev.jdesk.platform.macos;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.ffm.NativeHandle;
import dev.jdesk.webview.spi.CupsPrinting;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformWindow;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import dev.jdesk.api.MessageDialog;
import dev.jdesk.api.MessageDialogResult;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * One AppKit application. The process main thread is the UI thread (spec section 7:
 * NSApplication and AppKit event handling run on the first/main thread — launch with
 * {@code -XstartOnFirstThread}). Windows and WebViews are created on this thread, both
 * before {@code [NSApp run]} starts and later through the dispatcher while it runs.
 */
final class MacPlatformApplication extends NativeHandle implements PlatformApplication {
    private static final Logger LOG = System.getLogger(MacPlatformApplication.class.getName());

    private static final long NS_EVENT_TYPE_APPLICATION_DEFINED = 15; // NSEvent.h
    // + otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:
    private static final FunctionDescriptor OTHER_EVENT_DESC = FunctionDescriptor.of(ADDRESS,
            ADDRESS, ADDRESS, JAVA_LONG, ObjC.NSPOINT, JAVA_LONG, JAVA_DOUBLE, JAVA_LONG,
            ADDRESS, JAVA_SHORT, JAVA_LONG, JAVA_LONG);
    // - postEvent:atStart:
    private static final FunctionDescriptor POST_EVENT_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_BYTE);
    // - setActivationPolicy: (returns BOOL)
    private static final FunctionDescriptor SET_ACTIVATION_POLICY_DESC =
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG);

    private final PlatformApplicationConfig config;
    private final MacUiDispatcher dispatcher;
    /** Pins block literals/stubs for evaluate/snapshot completions; app-lifetime owner. */
    private final NativeCallbackRegistry blockRegistry;
    private final MemorySegment nsApp;
    private final MemorySegment constructionPool;
    private volatile boolean stopRequested;
    /** Lazily created so apps that never watch files never load CoreServices. */
    private volatile MacFsEventsBackend fileWatchBackend;
    private volatile MacPtyBackend ptyBackend;

    MacPlatformApplication(PlatformApplicationConfig config) {
        super("MacPlatformApplication");
        if (!MacUiDispatcher.onProcessMainThread()) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "AppKit requires the process main thread; launch the JVM with "
                            + "-XstartOnFirstThread");
        }
        this.config = config;
        this.constructionPool = ObjC.autoreleasePoolPush();
        String displayName = MacApplicationName.displayName(config.applicationId());
        applyProcessName(displayName);
        this.nsApp = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication");
        try {
            // NSApplicationActivationPolicyRegular = 0
            byte unusedPolicyResult = (byte) ObjC.msgSend(SET_ACTIVATION_POLICY_DESC)
                    .invokeExact(nsApp, ObjC.sel("setActivationPolicy:"), 0L);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
        ObjC.sendVoidBool(nsApp, "activateIgnoringOtherApps:", true);
        installApplicationMenu(displayName);
        this.dispatcher = new MacUiDispatcher(config.devMode());
        this.blockRegistry = new NativeCallbackRegistry("macos-app-blocks", Arena.ofShared());
        markOpen();
    }

    /**
     * Sets {@code NSProcessInfo.processName} so contexts that read it (some crash reports,
     * {@code ps} listings) show the app name instead of "java" on a raw-JVM dev launch.
     * Best-effort and cosmetic; the visible menu-bar name is handled separately by
     * {@link #installApplicationMenu(String)} (setting the process name alone does <em>not</em>
     * change the bold menu-bar title — verified live: macOS derives the auto app-menu title
     * from the executable/bundle name, not from {@code processName}).
     */
    private void applyProcessName(String name) {
        if (name == null) {
            return;
        }
        try {
            MemorySegment processInfo = ObjC.send(ObjC.cls("NSProcessInfo"), "processInfo");
            ObjC.sendVoid(processInfo, "setProcessName:", ObjC.nsString(name));
        } catch (RuntimeException e) {
            // Best-effort cosmetic; never block startup over the display name.
            LOG.log(System.Logger.Level.DEBUG, "Could not set process name", e);
        }
    }

    /**
     * Installs a standard application menu so every JDesk app has a real app menu with a
     * {@code Quit <Name>} item and a working ⌘Q — a raw FFM {@code NSApplication} otherwise has
     * only AppKit's bare auto-generated menu. The submenu title and the Quit item carry the app
     * name, so the app's identity shows in the one place the framework can control at runtime.
     *
     * <p><strong>Limitation (verified live, macOS ARM64, Homebrew OpenJDK 25):</strong> this does
     * <em>not</em> change the <em>bold application name</em> shown in the menu bar on a non-bundled
     * launch. AppKit forces that title to the executable name ("java") for a bundle-less process
     * and ignores the first menu item's title; {@code NSProcessInfo setProcessName:} and the
     * launcher flag {@code -Xdock:name} were both confirmed to have no effect on it (the latter
     * only renames AWT apps, which JDesk is not). The only way to change the bold name is a real
     * {@code .app} carrying {@code CFBundleName} — which {@code jpackage} produces, so packaged
     * apps already show the correct name. For dev {@code gradlew run} the bold name stays "java".
     *
     * <p>The Quit item is wired to {@code terminate:}. The runtime registers no JVM shutdown hook
     * of its own, so standard AppKit termination bypasses no cleanup.
     */
    private void installApplicationMenu(String name) {
        if (name == null) {
            return;
        }
        try {
            MemorySegment mainMenu = ObjC.send(ObjC.send(ObjC.cls("NSMenu"), "alloc"), "init");
            ObjC.autorelease(mainMenu);
            MemorySegment appItem = ObjC.send(ObjC.send(ObjC.cls("NSMenuItem"), "alloc"), "init");
            ObjC.autorelease(appItem);
            // AppKit forces the bold bar title to the executable name for a bundle-less process,
            // so this title only takes effect once the app is packaged (CFBundleName present).
            ObjC.sendVoid(appItem, "setTitle:", ObjC.nsString(name));
            ObjC.sendVoid(mainMenu, "addItem:", appItem);

            MemorySegment appMenu = ObjC.send(ObjC.send(ObjC.cls("NSMenu"), "alloc"),
                    "initWithTitle:", ObjC.nsString(name));
            ObjC.autorelease(appMenu);
            MemorySegment quitItem = ObjC.send(ObjC.send(ObjC.cls("NSMenuItem"), "alloc"), "init");
            ObjC.autorelease(quitItem);
            ObjC.sendVoid(quitItem, "setTitle:", ObjC.nsString("Quit " + name));
            ObjC.sendVoid(quitItem, "setKeyEquivalent:", ObjC.nsString("q"));
            ObjC.sendVoid(quitItem, "setAction:", ObjC.sel("terminate:"));
            ObjC.sendVoid(quitItem, "setTarget:", nsApp);
            ObjC.sendVoid(appMenu, "addItem:", quitItem);
            ObjC.sendVoid(appItem, "setSubmenu:", appMenu);

            // Standard Edit menu. On the jdesk://app custom scheme (WKWebView), macOS only routes
            // ⌘C/⌘X/⌘V/⌘A to the web content when these first-responder actions exist in the menu,
            // so a plain text field or a code editor has working clipboard out of the box. Items
            // carry a nil target: AppKit dispatches copy:/cut:/paste:/selectAll: up the responder
            // chain, which reaches the focused WKWebView.
            MemorySegment editItem = ObjC.send(
                    ObjC.send(ObjC.cls("NSMenuItem"), "alloc"), "init");
            ObjC.autorelease(editItem);
            ObjC.sendVoid(editItem, "setTitle:", ObjC.nsString("Edit"));
            ObjC.sendVoid(mainMenu, "addItem:", editItem);
            MemorySegment editMenu = ObjC.send(ObjC.send(ObjC.cls("NSMenu"), "alloc"),
                    "initWithTitle:", ObjC.nsString("Edit"));
            ObjC.autorelease(editMenu);
            addStandardMenuItem(editMenu, "Undo", "z", "undo:");
            addStandardMenuItem(editMenu, "Redo", "Z", "redo:"); // capital Z => ⇧⌘Z
            ObjC.sendVoid(editMenu, "addItem:",
                    ObjC.send(ObjC.cls("NSMenuItem"), "separatorItem"));
            addStandardMenuItem(editMenu, "Cut", "x", "cut:");
            addStandardMenuItem(editMenu, "Copy", "c", "copy:");
            addStandardMenuItem(editMenu, "Paste", "v", "paste:");
            addStandardMenuItem(editMenu, "Select All", "a", "selectAll:");
            ObjC.sendVoid(editItem, "setSubmenu:", editMenu);

            ObjC.sendVoid(nsApp, "setMainMenu:", mainMenu);
        } catch (RuntimeException e) {
            // Best-effort cosmetic; never block startup over the menu bar name.
            LOG.log(System.Logger.Level.DEBUG, "Could not install application menu", e);
        }
    }

    /** Adds a nil-targeted first-responder menu item (Edit-menu clipboard/undo actions). */
    private void addStandardMenuItem(MemorySegment menu, String title, String keyEquivalent,
            String selector) {
        MemorySegment item = ObjC.send(ObjC.send(ObjC.cls("NSMenuItem"), "alloc"), "init");
        ObjC.autorelease(item);
        ObjC.sendVoid(item, "setTitle:", ObjC.nsString(title));
        ObjC.sendVoid(item, "setKeyEquivalent:", ObjC.nsString(keyEquivalent));
        ObjC.sendVoid(item, "setAction:", ObjC.sel(selector));
        ObjC.sendVoid(menu, "addItem:", item);
    }

    @Override
    public UiDispatcher ui() {
        return dispatcher;
    }

    MacUiDispatcher dispatcher() {
        return dispatcher;
    }

    PlatformApplicationConfig config() {
        return config;
    }

    NativeCallbackRegistry blockRegistry() {
        return blockRegistry;
    }

    @Override
    public PlatformWindow createWindow(NativeWindowConfig windowConfig) {
        requireOpen();
        dispatcher.assertUiThread();
        return new MacWindow(this, windowConfig);
    }

    @Override public void openExternal(URI uri) {
        requireOpen(); dispatcher.assertUiThread();
        MemorySegment url = ObjC.send(ObjC.cls("NSURL"), "URLWithString:", ObjC.nsString(uri.toString()));
        boolean opened = ObjC.sendBool(ObjC.send(ObjC.cls("NSWorkspace"), "sharedWorkspace"),
                "openURL:", url);
        if (!opened) throw new JDeskException(ErrorCode.ILLEGAL_STATE, "OS refused external URI");
    }

    @Override public dev.jdesk.api.SecretStore secrets(String applicationId) {
        // Keychain Services calls are thread-safe; no UI-thread assertion by design.
        return new MacSecretStore(applicationId);
    }

    @Override
    public java.util.Optional<dev.jdesk.webview.spi.FileWatchBackend> fileWatchBackend() {
        MacFsEventsBackend backend = fileWatchBackend;
        if (backend == null) {
            synchronized (this) {
                backend = fileWatchBackend;
                if (backend == null) {
                    backend = new MacFsEventsBackend();
                    fileWatchBackend = backend;
                }
            }
        }
        return java.util.Optional.of(backend);
    }

    @Override
    public java.util.Optional<dev.jdesk.webview.spi.PtyBackend> ptyBackend() {
        MacPtyBackend backend = ptyBackend;
        if (backend == null) {
            synchronized (this) {
                backend = ptyBackend;
                if (backend == null) {
                    backend = new MacPtyBackend();
                    ptyBackend = backend;
                }
            }
        }
        return java.util.Optional.of(backend);
    }

    @Override public String readClipboardText() {
        requireOpen(); dispatcher.assertUiThread();
        MemorySegment pasteboard=ObjC.send(ObjC.cls("NSPasteboard"),"generalPasteboard");
        return ObjC.javaString(ObjC.send(pasteboard,"stringForType:",
                ObjC.nsString("public.utf8-plain-text")));
    }
    @Override public void writeClipboardText(String text) {
        requireOpen(); dispatcher.assertUiThread();
        MemorySegment pasteboard=ObjC.send(ObjC.cls("NSPasteboard"),"generalPasteboard");
        ObjC.sendLong(pasteboard,"clearContents");
        boolean ok=ObjC.sendBool(pasteboard,"setString:forType:",ObjC.nsString(text),
                ObjC.nsString("public.utf8-plain-text"));
        if(!ok)throw new JDeskException(ErrorCode.ILLEGAL_STATE,"Could not write clipboard");
    }

    @Override
    public dev.jdesk.api.SystemTheme systemTheme() {
        requireOpen();
        dispatcher.assertUiThread();
        MemorySegment appearance = ObjC.send(nsApp, "effectiveAppearance");
        String name = ObjC.javaString(ObjC.send(appearance, "name"));
        return name != null && name.contains("Dark")
                ? dev.jdesk.api.SystemTheme.DARK : dev.jdesk.api.SystemTheme.LIGHT;
    }

    @Override
    public byte[] readClipboard(String type) {
        requireOpen();
        dispatcher.assertUiThread();
        MemorySegment pasteboard = ObjC.send(ObjC.cls("NSPasteboard"), "generalPasteboard");
        MemorySegment data = ObjC.send(pasteboard, "dataForType:", ObjC.nsString(type));
        if (data == null || data.equals(MemorySegment.NULL)) {
            return null;
        }
        long length = ObjC.sendLong(data, "length");
        if (length <= 0) {
            return new byte[0];
        }
        MemorySegment bytes = ObjC.send(data, "bytes");
        if (bytes.equals(MemorySegment.NULL)) {
            return new byte[0];
        }
        return bytes.reinterpret(length).toArray(JAVA_BYTE);
    }

    @Override
    public void writeClipboard(String type, byte[] data) {
        requireOpen();
        dispatcher.assertUiThread();
        java.util.Objects.requireNonNull(data, "data");
        MemorySegment pasteboard = ObjC.send(ObjC.cls("NSPasteboard"), "generalPasteboard");
        ObjC.sendLong(pasteboard, "clearContents");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(Math.max(1, data.length));
            if (data.length > 0) {
                MemorySegment.copy(data, 0, buffer, JAVA_BYTE, 0, data.length);
            }
            MemorySegment nsData;
            try {
                nsData = (MemorySegment) ObjC.msgSend(FunctionDescriptor.of(
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)).invokeExact(
                        ObjC.cls("NSData"), ObjC.sel("dataWithBytes:length:"),
                        buffer, (long) data.length);
            } catch (Throwable t) {
                throw ObjC.rethrow(t);
            }
            boolean ok = ObjC.sendBool(pasteboard, "setData:forType:", nsData, ObjC.nsString(type));
            if (!ok) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Could not write binary clipboard data for type " + type);
            }
        }
    }

    @Override
    public void setDockBadge(String label) {
        requireOpen();
        dispatcher.assertUiThread();
        MemorySegment dockTile = ObjC.send(nsApp, "dockTile");
        MemorySegment value = label == null || label.isBlank()
                ? MemorySegment.NULL : ObjC.nsString(label);
        ObjC.sendVoid(dockTile, "setBadgeLabel:", value);
        ObjC.sendVoid(dockTile, "display");
    }

    @Override
    public void setApplicationMenu(dev.jdesk.api.MenuSpec menu,
            java.util.function.Consumer<String> onAction) {
        requireOpen();
        dispatcher.assertUiThread();
        MacMenu.install(nsApp, menu, onAction);
    }

    @Override
    public void setApplicationIcon(byte[] pngData) {
        requireOpen();
        dispatcher.assertUiThread();
        java.util.Objects.requireNonNull(pngData, "pngData");
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(Math.max(1, pngData.length));
            if (pngData.length > 0) {
                MemorySegment.copy(pngData, 0, buffer, JAVA_BYTE, 0, pngData.length);
            }
            MemorySegment nsData;
            try {
                nsData = (MemorySegment) ObjC.msgSend(FunctionDescriptor.of(
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)).invokeExact(
                        ObjC.cls("NSData"), ObjC.sel("dataWithBytes:length:"),
                        buffer, (long) pngData.length);
            } catch (Throwable t) {
                throw ObjC.rethrow(t);
            }
            MemorySegment image = ObjC.send(
                    ObjC.send(ObjC.cls("NSImage"), "alloc"), "initWithData:", nsData);
            if (image.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "Could not decode application icon image (expected PNG bytes)");
            }
            ObjC.autorelease(image);
            ObjC.sendVoid(nsApp, "setApplicationIconImage:", image);
            // Structural self-check: the icon really took.
            if (ObjC.send(nsApp, "applicationIconImage").equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Application icon was not set");
            }
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
    }

    @Override
    public dev.jdesk.webview.spi.TrayControl createTrayItem(dev.jdesk.api.TraySpec spec,
            java.util.function.Consumer<String> onAction) {
        requireOpen();
        dispatcher.assertUiThread();
        MacTray tray = MacTray.create(spec, onAction);
        if (!tray.installed()) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Status item was not installed");
        }
        return tray;
    }

    @Override
    public Runnable registerGlobalShortcut(String accelerator, Runnable callback) {
        requireOpen();
        dispatcher.assertUiThread();
        return MacGlobalShortcut.register(accelerator, callback);
    }

    @Override
    public java.util.concurrent.CompletionStage<dev.jdesk.api.NotificationResponse> showNotification(
            dev.jdesk.api.InteractiveNotification notification) {
        requireOpen();
        dispatcher.assertUiThread();
        return MacInteractiveNotification.show(notification);
    }

    @Override
    public boolean share(dev.jdesk.api.ShareContent content) {
        requireOpen();
        dispatcher.assertUiThread();
        return MacDesktopServices.share(content);
    }

    @Override
    public boolean biometricsAvailable() {
        requireOpen();
        dispatcher.assertUiThread();
        return MacDesktopServices.biometricsAvailable();
    }

    @Override
    public void showNotification(String title, String body) {
        requireOpen();
        dispatcher.assertUiThread();
        // NSUserNotification works without a signed bundle for dev; production should move to
        // UNUserNotificationCenter (needs a signed bundle + permission). A nil center means
        // even legacy delivery is unavailable here — report it honestly.
        MemorySegment center = ObjC.send(
                ObjC.cls("NSUserNotificationCenter"), "defaultUserNotificationCenter");
        if (center.equals(MemorySegment.NULL)) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "User notifications unavailable (needs a signed app bundle)");
        }
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            MemorySegment note = ObjC.send(
                    ObjC.send(ObjC.cls("NSUserNotification"), "alloc"), "init");
            ObjC.autorelease(note);
            ObjC.sendVoid(note, "setTitle:", ObjC.nsString(title == null ? "" : title));
            ObjC.sendVoid(note, "setInformativeText:", ObjC.nsString(body == null ? "" : body));
            ObjC.sendVoid(center, "deliverNotification:", note);
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
    }

    @Override
    public void setOpenUrlHandler(java.util.function.Consumer<java.net.URI> handler) {
        requireOpen();
        dispatcher.assertUiThread();
        MacOpenUrl.install(nsApp, handler);
    }

    @Override public MessageDialogResult showMessageDialog(MessageDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        MemorySegment alert = ObjC.send(ObjC.send(ObjC.cls("NSAlert"), "alloc"), "init");
        try {
            ObjC.sendVoid(alert, "setMessageText:", ObjC.nsString(dialog.title()));
            ObjC.sendVoid(alert, "setInformativeText:", ObjC.nsString(dialog.message()));
            long style = switch (dialog.kind()) { case WARNING -> 0L; case INFO -> 1L; case ERROR -> 2L; };
            ObjC.sendVoidLong(alert, "setAlertStyle:", style);
            for (String button : dialog.buttons()) ObjC.send(alert, "addButtonWithTitle:", ObjC.nsString(button));
            long response = ObjC.sendLong(alert, "runModal");
            int index = (int) (response - 1000L);
            if (index < 0 || index >= dialog.buttons().size()) index = 0;
            return new MessageDialogResult(index, dialog.buttons().get(index));
        } finally { ObjC.release(alert); }
    }

    private static final long NS_MODAL_RESPONSE_OK = 1L; // NSModalResponseOK

    @Override
    public dev.jdesk.api.FileDialogResult showOpenDialog(dev.jdesk.api.FileDialog.OpenDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        MemorySegment panel = ObjC.send(ObjC.cls("NSOpenPanel"), "openPanel");
        ObjC.sendVoidBool(panel, "setCanChooseFiles:", !dialog.chooseDirectories());
        ObjC.sendVoidBool(panel, "setCanChooseDirectories:", dialog.chooseDirectories());
        ObjC.sendVoidBool(panel, "setAllowsMultipleSelection:", dialog.allowMultiple());
        if (!dialog.title().isEmpty()) {
            ObjC.sendVoid(panel, "setMessage:", ObjC.nsString(dialog.title()));
        }
        dialog.directory().ifPresent(dir -> ObjC.sendVoid(panel, "setDirectoryURL:",
                ObjC.send(ObjC.cls("NSURL"), "fileURLWithPath:", ObjC.nsString(dir))));
        applyAllowedTypes(panel, dialog.filters());
        if (ObjC.sendLong(panel, "runModal") != NS_MODAL_RESPONSE_OK) {
            return dev.jdesk.api.FileDialogResult.cancelled();
        }
        MemorySegment urls = ObjC.send(panel, "URLs");
        long count = ObjC.sendLong(urls, "count");
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (long i = 0; i < count; i++) {
            MemorySegment url = ObjC.sendIndexed(urls, "objectAtIndex:", i);
            String path = ObjC.javaString(ObjC.send(url, "path"));
            if (path != null && !path.isEmpty()) {
                paths.add(path);
            }
        }
        return new dev.jdesk.api.FileDialogResult(paths);
    }

    @Override
    public dev.jdesk.api.FileDialogResult showSaveDialog(dev.jdesk.api.FileDialog.SaveDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        MemorySegment panel = ObjC.send(ObjC.cls("NSSavePanel"), "savePanel");
        if (!dialog.title().isEmpty()) {
            ObjC.sendVoid(panel, "setMessage:", ObjC.nsString(dialog.title()));
        }
        dialog.directory().ifPresent(dir -> ObjC.sendVoid(panel, "setDirectoryURL:",
                ObjC.send(ObjC.cls("NSURL"), "fileURLWithPath:", ObjC.nsString(dir))));
        dialog.suggestedName().ifPresent(name ->
                ObjC.sendVoid(panel, "setNameFieldStringValue:", ObjC.nsString(name)));
        applyAllowedTypes(panel, dialog.filters());
        if (ObjC.sendLong(panel, "runModal") != NS_MODAL_RESPONSE_OK) {
            return dev.jdesk.api.FileDialogResult.cancelled();
        }
        String path = ObjC.javaString(ObjC.send(ObjC.send(panel, "URL"), "path"));
        return path == null || path.isEmpty()
                ? dev.jdesk.api.FileDialogResult.cancelled()
                : new dev.jdesk.api.FileDialogResult(java.util.List.of(path));
    }

    /** Applies the union of filter extensions as the panel's allowed file types. */
    private void applyAllowedTypes(MemorySegment panel, java.util.List<dev.jdesk.api.FileDialog.Filter> filters) {
        java.util.List<String> extensions = filters.stream()
                .flatMap(f -> f.extensions().stream())
                .filter(ext -> !ext.isBlank())
                .distinct()
                .toList();
        if (extensions.isEmpty()) {
            return; // no filter: any file
        }
        MemorySegment array = ObjC.send(ObjC.cls("NSMutableArray"), "array");
        for (String ext : extensions) {
            ObjC.sendVoid(array, "addObject:", ObjC.nsString(ext));
        }
        // setAllowedFileTypes: is deprecated but still functional and avoids the
        // UTType dependency; extensions without the leading dot are accepted.
        ObjC.sendVoid(panel, "setAllowedFileTypes:", array);
    }

    @Override
    public void printFile(dev.jdesk.api.PrintJob job) {
        CupsPrinting.printFile(job); // CUPS lp; thread-safe, runs off the UI thread
    }

    @Override
    public void runEventLoop() {
        requireOpen();
        dispatcher.assertUiThread();
        if (stopRequested) {
            return;
        }
        ObjC.sendVoid(nsApp, "run");
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        dispatcher.execute(() -> {
            try {
                ObjC.sendVoid(nsApp, "stop:", MemorySegment.NULL);
                postWakeEvent();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "requestStop failed", e);
            }
        });
    }

    /**
     * {@code [NSApp stop:]} only takes effect after the current event completes; posting
     * an application-defined event wakes the loop (documented AppKit pattern).
     */
    private void postWakeEvent() {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment location = confined.allocate(ObjC.NSPOINT); // zero-initialized
            MemorySegment event = (MemorySegment) ObjC.msgSend(OTHER_EVENT_DESC).invokeExact(
                    ObjC.cls("NSEvent"),
                    ObjC.sel("otherEventWithType:location:modifierFlags:timestamp:"
                            + "windowNumber:context:subtype:data1:data2:"),
                    NS_EVENT_TYPE_APPLICATION_DEFINED, location, 0L, 0.0d, 0L,
                    MemorySegment.NULL, (short) 0, 0L, 0L);
            if (!event.equals(MemorySegment.NULL)) {
                ObjC.msgSend(POST_EVENT_DESC).invokeExact(
                        nsApp, ObjC.sel("postEvent:atStart:"), event, (byte) 1);
            }
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }

    @Override
    protected void releaseNative() {
        dispatcher.assertUiThread();
        blockRegistry.close();
        ObjC.autoreleasePoolPop(constructionPool);
        // NSApplication is a process singleton and is not released.
    }
}
