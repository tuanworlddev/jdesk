package dev.jdesk.platform.windows;

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
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** One Win32 top-level window hosting a WebView2 controller. UI-thread only. */
final class WindowsWindow extends NativeHandle implements PlatformWindow {
    private static final Logger LOG = System.getLogger(WindowsWindow.class.getName());
    private static final String WINDOW_CLASS = "JDeskWindow";
    private static final int WM_DROPFILES = 0x0233;
    private static final AtomicBoolean CLASS_REGISTERED = new AtomicBoolean();
    /** WndProc dispatch table: HWND address -> window. */
    private static final Map<Long, WindowsWindow> WINDOWS = new ConcurrentHashMap<>();
    private static volatile MemorySegment sharedWndProcStub;
    private static final Arena CLASS_ARENA = Arena.ofShared();

    private final WindowsPlatformApplication app;
    private final WindowId id;
    private final NativeCallbackRegistry registry;
    private final MemorySegment hwnd;
    private final WindowsWebView webView;
    private final List<BooleanSupplier> closeRequestedHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> closedHandlers = new CopyOnWriteArrayList<>();
    private final int minWidth;
    private final int minHeight;
    private volatile java.util.function.Consumer<List<java.nio.file.Path>> fileDropListener;
    private boolean destroyed;

    WindowsWindow(WindowsPlatformApplication app, NativeWindowConfig config) {
        super("WindowsWindow[" + config.id() + "]");
        this.app = app;
        this.id = config.id();
        this.minWidth = config.minWidth();
        this.minHeight = config.minHeight();
        this.registry = new NativeCallbackRegistry("window-" + config.id(), Arena.ofShared());

        ensureWindowClass();
        try (Arena confined = Arena.ofConfined()) {
            int style = config.resizable()
                    ? Win32.WS_OVERLAPPEDWINDOW
                    : Win32.WS_OVERLAPPED_NO_RESIZE;
            this.hwnd = Win32.createWindowEx(0,
                    WideStrings.alloc(confined, WINDOW_CLASS),
                    WideStrings.alloc(confined, config.title()),
                    style,
                    Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT,
                    config.width(), config.height(),
                    MemorySegment.NULL, MemorySegment.NULL,
                    Win32.getModuleHandle(), MemorySegment.NULL);
        }
        if (hwnd.equals(MemorySegment.NULL)) {
            registry.close();
            throw new IllegalStateException("CreateWindowExW failed for " + config.id());
        }
        WINDOWS.put(hwnd.address(), this);
        try {
            this.webView = new WindowsWebView(app, this, config);
        } catch (RuntimeException e) {
            WINDOWS.remove(hwnd.address());
            Win32.destroyWindow(hwnd);
            registry.close();
            throw e;
        }
        markOpen();
    }

    private static void ensureWindowClass() {
        if (!CLASS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            var wndProc = MethodHandles.lookup().findStatic(WindowsWindow.class, "wndProc",
                    MethodType.methodType(long.class, MemorySegment.class, int.class,
                            long.class, long.class));
            sharedWndProcStub = Linker.nativeLinker().upcallStub(
                    wndProc, Win32.WNDPROC_DESC, CLASS_ARENA);

            MemorySegment wndClass = CLASS_ARENA.allocate(Win32.WNDCLASSEXW);
            wndClass.set(JAVA_INT, 0, (int) Win32.WNDCLASSEXW.byteSize());
            wndClass.set(JAVA_INT, 4, 0x0003); // CS_HREDRAW | CS_VREDRAW
            wndClass.set(ADDRESS, 8, sharedWndProcStub);
            wndClass.set(ADDRESS, 24, Win32.getModuleHandle());
            wndClass.set(ADDRESS, 40, Win32.loadArrowCursor());
            wndClass.set(ADDRESS, 48, MemorySegment.ofAddress(6)); // COLOR_WINDOW+1 brush
            wndClass.set(ADDRESS, 64, WideStrings.alloc(CLASS_ARENA, WINDOW_CLASS));
            short atom = Win32.registerClassEx(wndClass);
            if (atom == 0) {
                throw new IllegalStateException("RegisterClassExW failed for " + WINDOW_CLASS);
            }
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused") // shared upcall for all JDeskWindow instances
    static long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        WindowsWindow window = WINDOWS.get(hwnd.address());
        if (window == null) {
            return Win32.defWindowProc(hwnd, msg, wParam, lParam);
        }
        try {
            switch (msg) {
                case Win32.WM_CLOSE -> {
                    for (BooleanSupplier handler : window.closeRequestedHandlers) {
                        if (!handler.getAsBoolean()) {
                            return 0; // vetoed
                        }
                    }
                    window.close();
                    return 0;
                }
                case Win32.WM_SIZE -> {
                    // webView is still null while the controller is being created
                    // (nested pump during construction).
                    if (window.webView != null) {
                        window.webView.resizeToClientArea();
                    }
                    return 0;
                }
                case Win32.WM_GETMINMAXINFO -> {
                    if (window.minWidth > 0 || window.minHeight > 0) {
                        // MINMAXINFO*: ptMinTrackSize POINT at offset 24 (x=24, y=28).
                        MemorySegment info = MemorySegment.ofAddress(lParam).reinterpret(40);
                        if (window.minWidth > 0) {
                            info.set(JAVA_INT, 24, window.minWidth);
                        }
                        if (window.minHeight > 0) {
                            info.set(JAVA_INT, 28, window.minHeight);
                        }
                        return 0;
                    }
                    return Win32.defWindowProc(hwnd, msg, wParam, lParam);
                }
                case WM_DROPFILES -> {
                    var listener = window.fileDropListener;
                    if (listener != null) {
                        var paths = WindowsFileDrop.extractPaths(wParam); // wParam is the HDROP
                        if (!paths.isEmpty()) {
                            listener.accept(paths);
                        }
                    }
                    return 0;
                }
                case Win32.WM_DESTROY -> {
                    window.onDestroyed();
                    return 0;
                }
                default -> {
                    return Win32.defWindowProc(hwnd, msg, wParam, lParam);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "WndProc failed for {0} msg=0x{1}",
                    window.id, Integer.toHexString(msg), e);
            return Win32.defWindowProc(hwnd, msg, wParam, lParam);
        }
    }

    MemorySegment hwnd() {
        return hwnd;
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
    public java.util.Optional<String> showContextMenu(dev.jdesk.api.MenuSpec menu) {
        requireOpen();
        return WindowsMenu.showContextMenu(hwnd, menu);
    }

    @Override
    public Runnable onFileDrop(
            java.util.function.Consumer<List<java.nio.file.Path>> listener) {
        requireOpen();
        this.fileDropListener = listener;
        WindowsFileDrop.setAccept(hwnd, true);
        return () -> {
            this.fileDropListener = null;
            if (!destroyed) {
                WindowsFileDrop.setAccept(hwnd, false);
            }
        };
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
        Win32.showWindow(hwnd, Win32.SW_SHOW);
        webView.resizeToClientArea();
    }

    @Override
    public void hide() {
        requireOpen();
        Win32.showWindow(hwnd, Win32.SW_HIDE);
    }

    @Override public void focus() { requireOpen(); Win32.focusWindow(hwnd); }
    @Override public void setMinimized(boolean value) {
        requireOpen(); if (Win32.isIconic(hwnd) != value) Win32.showWindow(hwnd, value ? 6 : 9);
    }
    @Override public void setMaximized(boolean value) {
        requireOpen(); if (Win32.isZoomed(hwnd) != value) Win32.showWindow(hwnd, value ? 3 : 9);
    }
    @Override public void setFullscreen(boolean value) {
        // Borderless fullscreen needs monitor/style restoration and is intentionally
        // separate from maximize. The helper owns that state per HWND.
        requireOpen(); WindowsFullscreen.set(hwnd, value);
        webView.resizeToClientArea();
    }
    @Override public void setAlwaysOnTop(boolean value) {
        requireOpen(); Win32.setWindowPosAfter(hwnd, value ? -1L : -2L, 0x0001 | 0x0002);
    }

    @Override
    public void setTitle(String title) {
        requireOpen();
        try (Arena confined = Arena.ofConfined()) {
            Win32.setWindowText(hwnd, WideStrings.alloc(confined, title));
        }
    }

    @Override
    public void setBounds(WindowBounds bounds) {
        requireOpen();
        Win32.setWindowPos(hwnd, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0);
    }

    @Override
    public WindowBounds getBounds() {
        requireOpen();
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment rect = confined.allocate(Win32.RECT);
            Win32.getWindowRect(hwnd, rect);
            int left = rect.get(JAVA_INT, 0);
            int top = rect.get(JAVA_INT, 4);
            int right = rect.get(JAVA_INT, 8);
            int bottom = rect.get(JAVA_INT, 12);
            return new WindowBounds(left, top, right - left, bottom - top);
        }
    }

    /** WM_DESTROY: notify listeners exactly once, then release the WebView pipeline. */
    private void onDestroyed() {
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
        WINDOWS.remove(hwnd.address());
        webView.destroyFromWindow();
        registry.close();
    }

    @Override
    protected void releaseNative() {
        // Close is UI-thread only (enforced by dispatcher use in the runtime layer).
        if (!destroyed) {
            Win32.destroyWindow(hwnd); // triggers WM_DESTROY -> onDestroyed()
        }
    }

    /** Client-area rectangle in pixels. */
    WindowBounds clientBounds() {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment rect = confined.allocate(Win32.RECT);
            Win32.getClientRect(hwnd, rect);
            int right = rect.get(JAVA_INT, 8);
            int bottom = rect.get(JAVA_INT, 12);
            return new WindowBounds(0, 0, right, bottom);
        }
    }

}
