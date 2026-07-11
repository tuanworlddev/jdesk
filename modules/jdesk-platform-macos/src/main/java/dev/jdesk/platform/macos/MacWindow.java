package dev.jdesk.platform.macos;

import dev.jdesk.api.Subscription;
import dev.jdesk.api.WindowId;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.ffm.NativeHandle;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.PlatformWebView;
import dev.jdesk.webview.spi.PlatformWindow;
import dev.jdesk.webview.spi.WindowBounds;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** One NSWindow hosting a WKWebView content view. UI-thread (main thread) only. */
final class MacWindow extends NativeHandle implements PlatformWindow {
    private static final Logger LOG = System.getLogger(MacWindow.class.getName());

    // NSWindow.h style masks
    private static final long STYLE_TITLED = 1;
    private static final long STYLE_CLOSABLE = 1 << 1;
    private static final long STYLE_MINIATURIZABLE = 1 << 2;
    private static final long STYLE_RESIZABLE = 1 << 3;
    private static final long BACKING_STORE_BUFFERED = 2; // NSBackingStoreBuffered

    // - initWithContentRect:styleMask:backing:defer:
    private static final FunctionDescriptor INIT_WINDOW_DESC = FunctionDescriptor.of(ADDRESS,
            ADDRESS, ADDRESS, ObjC.NSRECT, JAVA_LONG, JAVA_LONG, JAVA_BYTE);
    // - setContentSize:
    private static final FunctionDescriptor SET_CONTENT_SIZE_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ObjC.NSSIZE);
    // - setFrameTopLeftPoint:
    private static final FunctionDescriptor SET_TOP_LEFT_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ObjC.NSPOINT);
    // - frame (NSRect return: plain objc_msgSend on arm64, struct layout in descriptor)
    private static final FunctionDescriptor FRAME_DESC =
            FunctionDescriptor.of(ObjC.NSRECT, ADDRESS, ADDRESS);

    /** Delegate-instance address -> window peer (same pattern as the Windows HWND map). */
    private static final Map<Long, MacWindow> PEERS = new ConcurrentHashMap<>();
    private static final Object CLASS_LOCK = new Object();
    private static MemorySegment delegateClass;

    private final MacPlatformApplication app;
    private final WindowId id;
    private final NativeCallbackRegistry registry;
    private final MemorySegment nsWindow; // owned; releasedWhenClosed = NO
    private final MemorySegment delegate; // owned (+1); NSWindow.delegate is weak
    private final MacWebView webView;
    private final List<BooleanSupplier> closeRequestedHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> closedHandlers = new CopyOnWriteArrayList<>();
    private boolean destroyed;

    MacWindow(MacPlatformApplication app, NativeWindowConfig config) {
        super("MacWindow[" + config.id() + "]");
        this.app = app;
        this.id = config.id();
        this.registry = new NativeCallbackRegistry("window-" + config.id(), Arena.ofShared());
        ensureDelegateClass();

        MemorySegment window;
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment rect = confined.allocate(ObjC.NSRECT);
            rect.set(JAVA_DOUBLE, 16, config.width());
            rect.set(JAVA_DOUBLE, 24, config.height());
            long style = STYLE_TITLED | STYLE_CLOSABLE | STYLE_MINIATURIZABLE
                    | (config.resizable() ? STYLE_RESIZABLE : 0);
            window = (MemorySegment) ObjC.msgSend(INIT_WINDOW_DESC).invokeExact(
                    ObjC.send(ObjC.cls("NSWindow"), "alloc"),
                    ObjC.sel("initWithContentRect:styleMask:backing:defer:"),
                    rect, style, BACKING_STORE_BUFFERED, (byte) 0);
        } catch (Throwable t) {
            registry.close();
            throw ObjC.rethrow(t);
        }
        if (window.equals(MemorySegment.NULL)) {
            registry.close();
            throw new IllegalStateException("NSWindow initialization failed for " + config.id());
        }
        this.nsWindow = window;
        // We manage the window's single owning reference explicitly (avoids the
        // close-time double free of the default releasedWhenClosed behavior).
        ObjC.sendVoidBool(nsWindow, "setReleasedWhenClosed:", false);
        ObjC.sendVoid(nsWindow, "setTitle:", ObjC.nsString(config.title()));
        ObjC.sendVoid(nsWindow, "center");

        this.delegate = ObjC.send(ObjC.send(delegateClass, "alloc"), "init");
        PEERS.put(delegate.address(), this);
        registry.register(new NativeCallbackRegistry.Registration(
                "windowDelegate", this, MethodHandles.constant(Object.class, null),
                delegate, null, () -> PEERS.remove(delegate.address())));
        ObjC.sendVoid(nsWindow, "setDelegate:", delegate);

        try {
            this.webView = new MacWebView(app, this, config);
        } catch (RuntimeException e) {
            ObjC.sendVoid(nsWindow, "setDelegate:", MemorySegment.NULL);
            PEERS.remove(delegate.address());
            ObjC.release(delegate);
            ObjC.release(nsWindow);
            registry.close();
            throw e;
        }
        ObjC.sendVoid(nsWindow, "setContentView:", webView.nsView());
        markOpen();
    }

    private static void ensureDelegateClass() {
        synchronized (CLASS_LOCK) {
            if (delegateClass != null) {
                return;
            }
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                delegateClass = new ObjCClassBuilder("JDeskWindowDelegate")
                        .protocol("NSWindowDelegate")
                        .method("windowShouldClose:", "B@:@",
                                FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS),
                                lookup.findStatic(MacWindow.class, "impWindowShouldClose",
                                        MethodType.methodType(byte.class, MemorySegment.class,
                                                MemorySegment.class, MemorySegment.class)))
                        .method("windowWillClose:", "v@:@",
                                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
                                lookup.findStatic(MacWindow.class, "impWindowWillClose",
                                        MethodType.methodType(void.class, MemorySegment.class,
                                                MemorySegment.class, MemorySegment.class)))
                        .register();
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // Window delegate IMPs are deliberately ungated (peer-map controlled, stubs are
    // process-lifetime): the willClose path closes the registry gate itself and would
    // otherwise deadlock awaiting its own in-flight entry — same structure as the
    // Windows WndProc.

    @SuppressWarnings("unused") // IMP upcall
    static byte impWindowShouldClose(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        MacWindow window = PEERS.get(self.address());
        if (window == null) {
            return 1;
        }
        try {
            for (BooleanSupplier handler : window.closeRequestedHandlers) {
                if (!handler.getAsBoolean()) {
                    LOG.log(Level.INFO, "Close request vetoed for {0}", window.id);
                    return 0;
                }
            }
            return 1;
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "windowShouldClose handler failed for {0}", window.id, t);
            return 1;
        }
    }

    @SuppressWarnings("unused") // IMP upcall
    static void impWindowWillClose(MemorySegment self, MemorySegment cmd,
            MemorySegment notification) {
        MacWindow window = PEERS.get(self.address());
        if (window == null) {
            return;
        }
        try {
            window.onWillClose();
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "windowWillClose teardown failed for {0}", window.id, t);
        }
    }

    /** Notifies listeners exactly once, then releases the WebView pipeline and window. */
    private void onWillClose() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        for (Runnable handler : closedHandlers) {
            try {
                handler.run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "onClosed handler failed for {0}", id, e);
            }
        }
        webView.destroyFromWindow();
        ObjC.sendVoid(nsWindow, "setDelegate:", MemorySegment.NULL);
        registry.close(); // detaches: removes the delegate peer mapping
        // AppKit is still inside [NSWindow close]; defer the final releases to the
        // enclosing autorelease pool instead of freeing under a live native frame.
        ObjC.autorelease(delegate);
        ObjC.autorelease(nsWindow);
        close(); // drive NEW/OPEN -> CLOSED; releaseNative below no-ops (destroyed)
    }

    NativeCallbackRegistry callbackRegistry() {
        return registry;
    }

    @Override
    public WindowId id() {
        return id;
    }

    @Override
    public PlatformWebView webView() {
        return webView;
    }

    @Override
    public Subscription onCloseRequested(BooleanSupplier handler) {
        closeRequestedHandlers.add(handler);
        return () -> closeRequestedHandlers.remove(handler);
    }

    @Override
    public Subscription onClosed(Runnable handler) {
        closedHandlers.add(handler);
        return () -> closedHandlers.remove(handler);
    }

    @Override
    public void show() {
        requireOpen();
        ObjC.sendVoid(nsWindow, "makeKeyAndOrderFront:", MemorySegment.NULL);
    }

    @Override
    public void hide() {
        requireOpen();
        ObjC.sendVoid(nsWindow, "orderOut:", MemorySegment.NULL);
    }

    @Override
    public void setTitle(String title) {
        requireOpen();
        ObjC.sendVoid(nsWindow, "setTitle:", ObjC.nsString(title));
    }

    @Override
    public void setBounds(WindowBounds bounds) {
        requireOpen();
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment size = confined.allocate(ObjC.NSSIZE);
            size.set(JAVA_DOUBLE, 0, bounds.width());
            size.set(JAVA_DOUBLE, 8, bounds.height());
            ObjC.msgSend(SET_CONTENT_SIZE_DESC).invokeExact(
                    nsWindow, ObjC.sel("setContentSize:"), size);

            // Logical bounds use top-left origin; AppKit uses bottom-left screen
            // coordinates, so flip against the main screen's top edge.
            MemorySegment screen = ObjC.send(ObjC.cls("NSScreen"), "mainScreen");
            if (!screen.equals(MemorySegment.NULL)) {
                MemorySegment frame = (MemorySegment) ObjC.msgSend(FRAME_DESC).invokeExact(
                        (SegmentAllocator) confined, screen, ObjC.sel("frame"));
                double screenTop = frame.get(JAVA_DOUBLE, 8) + frame.get(JAVA_DOUBLE, 24);
                MemorySegment topLeft = confined.allocate(ObjC.NSPOINT);
                topLeft.set(JAVA_DOUBLE, 0, bounds.x());
                topLeft.set(JAVA_DOUBLE, 8, screenTop - bounds.y());
                ObjC.msgSend(SET_TOP_LEFT_DESC).invokeExact(
                        nsWindow, ObjC.sel("setFrameTopLeftPoint:"), topLeft);
            }
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }

    @Override
    protected void releaseNative() {
        if (!destroyed) {
            // Triggers windowShouldClose-less close: [NSWindow close] never consults the
            // delegate's veto — correct for programmatic close — then windowWillClose
            // runs the teardown above synchronously.
            ObjC.sendVoid(nsWindow, "close");
        }
    }
}
