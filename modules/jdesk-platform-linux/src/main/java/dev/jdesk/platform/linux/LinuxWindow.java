package dev.jdesk.platform.linux;

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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** One GtkWindow hosting a WebKitWebView. UI-thread (GTK main thread) only. */
final class LinuxWindow extends NativeHandle implements PlatformWindow {
    private static final Logger LOG = System.getLogger(LinuxWindow.class.getName());

    // gboolean (*)(GtkWidget*, GdkEvent*, gpointer) — "delete-event"
    private static final FunctionDescriptor DELETE_EVENT_DESC =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    // void (*)(GtkWidget*, gpointer) — "destroy"
    private static final FunctionDescriptor DESTROY_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /**
     * GtkWindow address -> window peer. The delete-event/destroy trampolines are
     * process-lifetime static stubs dispatching through this map, deliberately ungated:
     * the destroy path closes the window's registry gate itself and would otherwise
     * deadlock awaiting its own in-flight entry — same structure as the Windows WndProc
     * and the macOS window delegate.
     */
    private static final Map<Long, LinuxWindow> PEERS = new ConcurrentHashMap<>();
    private static final MemorySegment DELETE_EVENT_STUB;
    private static final MemorySegment DESTROY_STUB;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            DELETE_EVENT_STUB = Gtk.upcall(lookup.findStatic(LinuxWindow.class,
                            "onDeleteEvent", MethodType.methodType(int.class,
                                    MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class)),
                    DELETE_EVENT_DESC);
            DESTROY_STUB = Gtk.upcall(lookup.findStatic(LinuxWindow.class, "onDestroySignal",
                            MethodType.methodType(void.class, MemorySegment.class,
                                    MemorySegment.class)),
                    DESTROY_DESC);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private final WindowId id;
    private final NativeCallbackRegistry registry;
    private final MemorySegment gtkWindow; // + one owned ref (g_object_ref)
    private final LinuxWebView webView;
    private final List<BooleanSupplier> closeRequestedHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> closedHandlers = new CopyOnWriteArrayList<>();
    private boolean destroyed;

    LinuxWindow(LinuxPlatformApplication app, NativeWindowConfig config) {
        super("LinuxWindow[" + config.id() + "]");
        this.id = config.id();
        this.registry = new NativeCallbackRegistry("window-" + config.id(), Arena.ofShared());

        MemorySegment window;
        try (Arena confined = Arena.ofConfined()) {
            window = (MemorySegment) Gtk.GTK_WINDOW_NEW.invokeExact(Gtk.GTK_WINDOW_TOPLEVEL);
            if (window.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("gtk_window_new failed for " + config.id());
            }
            // GTK owns the toplevel's initial reference until destroy; hold our own so
            // Java-side calls stay valid throughout teardown (spec 6.4: ref/unref pairs).
            MemorySegment unusedRef = Gtk.gObjectRef(window);
            Gtk.GTK_WINDOW_SET_TITLE.invokeExact(window, confined.allocateFrom(config.title()));
            Gtk.GTK_WINDOW_SET_DEFAULT_SIZE.invokeExact(window,
                    config.width(), config.height());
            Gtk.GTK_WINDOW_SET_RESIZABLE.invokeExact(window, config.resizable() ? 1 : 0);
        } catch (Throwable t) {
            registry.close();
            throw Gtk.rethrow(t);
        }
        this.gtkWindow = window;
        PEERS.put(window.address(), this);
        long deleteEventId = Gtk.signalConnect(window, "delete-event", DELETE_EVENT_STUB);
        long destroyId = Gtk.signalConnect(window, "destroy", DESTROY_STUB);
        registry.register(new NativeCallbackRegistry.Registration(
                "window-peer", this, MethodHandles.constant(Object.class, null),
                DESTROY_STUB, null, () -> PEERS.remove(window.address())));
        registry.register(new NativeCallbackRegistry.Registration(
                "delete-event", this, MethodHandles.constant(Object.class, null),
                DELETE_EVENT_STUB, deleteEventId,
                () -> Gtk.signalDisconnect(window, deleteEventId)));
        registry.register(new NativeCallbackRegistry.Registration(
                "destroy", this, MethodHandles.constant(Object.class, null),
                DESTROY_STUB, destroyId,
                () -> Gtk.signalDisconnect(window, destroyId)));

        try {
            this.webView = new LinuxWebView(app, this, config);
        } catch (RuntimeException e) {
            registry.close(); // disconnects window signals, removes the peer mapping
            Gtk.gObjectUnref(window);
            try {
                Gtk.GTK_WIDGET_DESTROY.invokeExact(window);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "Window cleanup after WebView failure also failed",
                        Gtk.rethrow(t));
            }
            throw e;
        }
        try {
            Gtk.GTK_CONTAINER_ADD.invokeExact(gtkWindow, webView.widget());
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
        markOpen();
    }

    // ---- signal trampolines (GTK main thread; copy data, return fast, never throw) ----

    @SuppressWarnings("unused") // GCallback upcall
    static int onDeleteEvent(MemorySegment widget, MemorySegment event, MemorySegment userData) {
        LinuxWindow window = PEERS.get(widget.address());
        if (window == null) {
            return 0; // proceed with default handling (destroy)
        }
        try {
            for (BooleanSupplier handler : window.closeRequestedHandlers) {
                if (!handler.getAsBoolean()) {
                    LOG.log(Level.INFO, "Close request vetoed for {0}", window.id);
                    return 1; // TRUE: stop emission, keep the window
                }
            }
            return 0; // FALSE: continue to default handler, which destroys the window
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "delete-event handler failed for {0}", window.id, t);
            return 0;
        }
    }

    @SuppressWarnings("unused") // GCallback upcall
    static void onDestroySignal(MemorySegment widget, MemorySegment userData) {
        LinuxWindow window = PEERS.get(widget.address());
        if (window == null) {
            return;
        }
        try {
            window.onDestroyed();
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "destroy teardown failed for {0}", window.id, t);
        }
    }

    /** Notifies listeners exactly once, then releases the WebView pipeline and window. */
    private void onDestroyed() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        LOG.log(Level.INFO, "Window destroyed: {0}", id);
        for (Runnable handler : closedHandlers) {
            try {
                handler.run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "onClosed handler failed for {0}", id, e);
            }
        }
        webView.destroyFromWindow();
        registry.close(); // reverse order: disconnect signals, then drop the peer mapping
        Gtk.gObjectUnref(gtkWindow); // balances the constructor g_object_ref
        close(); // drive NEW/OPEN -> CLOSED; releaseNative below no-ops (destroyed)
    }

    NativeCallbackRegistry callbackRegistry() {
        return registry;
    }

    MemorySegment handle() {
        return gtkWindow;
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
        try {
            Gtk.GTK_WIDGET_SHOW_ALL.invokeExact(gtkWindow);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override
    public void hide() {
        requireOpen();
        try {
            Gtk.GTK_WIDGET_HIDE.invokeExact(gtkWindow);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override public void focus() { call(Gtk.GTK_WINDOW_PRESENT); }
    @Override public void setMinimized(boolean value) {
        call(value ? Gtk.GTK_WINDOW_ICONIFY : Gtk.GTK_WINDOW_DEICONIFY);
    }
    @Override public void setMaximized(boolean value) {
        call(value ? Gtk.GTK_WINDOW_MAXIMIZE : Gtk.GTK_WINDOW_UNMAXIMIZE);
    }
    @Override public void setFullscreen(boolean value) {
        call(value ? Gtk.GTK_WINDOW_FULLSCREEN : Gtk.GTK_WINDOW_UNFULLSCREEN);
    }
    @Override public void setAlwaysOnTop(boolean value) {
        requireOpen(); try { Gtk.GTK_WINDOW_SET_KEEP_ABOVE.invokeExact(gtkWindow, value ? 1 : 0); }
        catch (Throwable t) { throw Gtk.rethrow(t); }
    }

    private void call(java.lang.invoke.MethodHandle handle) {
        requireOpen(); try { handle.invokeExact(gtkWindow); }
        catch (Throwable t) { throw Gtk.rethrow(t); }
    }

    @Override
    public void setTitle(String title) {
        requireOpen();
        try (Arena confined = Arena.ofConfined()) {
            Gtk.GTK_WINDOW_SET_TITLE.invokeExact(gtkWindow, confined.allocateFrom(title));
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override
    public void setBounds(WindowBounds bounds) {
        requireOpen();
        try {
            Gtk.GTK_WINDOW_RESIZE.invokeExact(gtkWindow, bounds.width(), bounds.height());
            Gtk.GTK_WINDOW_MOVE.invokeExact(gtkWindow, bounds.x(), bounds.y());
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    @Override
    protected void releaseNative() {
        if (!destroyed) {
            // Programmatic close never consults the delete-event veto (correct for
            // application-driven close); the destroy signal runs the teardown above
            // synchronously on this thread.
            try {
                Gtk.GTK_WIDGET_DESTROY.invokeExact(gtkWindow);
            } catch (Throwable t) {
                throw Gtk.rethrow(t);
            }
        }
    }
}
