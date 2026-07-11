package dev.jdesk.platform.windows;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.UiDispatcher;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Marshals work onto the Win32 STA/UI thread through a hidden message-only window:
 * {@link #execute} enqueues the runnable and posts {@code WM_APP}; the window's WndProc
 * drains the queue on the UI thread. Robust against modal message loops (unlike
 * PostThreadMessage, window messages are delivered inside nested pumps).
 */
final class WindowsUiDispatcher implements UiDispatcher {
    private static final Logger LOG = System.getLogger(WindowsUiDispatcher.class.getName());
    private static final String DISPATCH_CLASS = "JDeskDispatch";

    private final Thread uiThread;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Arena arena = Arena.ofShared();
    private final MemorySegment hwnd;
    private final boolean strictThreadChecks;

    /** Must be constructed on the UI thread. */
    WindowsUiDispatcher(boolean strictThreadChecks) {
        this.uiThread = Thread.currentThread();
        this.strictThreadChecks = strictThreadChecks;
        try {
            var wndProc = MethodHandles.lookup().findVirtual(WindowsUiDispatcher.class,
                    "wndProc", MethodType.methodType(long.class, MemorySegment.class,
                            int.class, long.class, long.class)).bindTo(this);
            MemorySegment stub = Linker.nativeLinker().upcallStub(
                    wndProc, Win32.WNDPROC_DESC, arena);

            MemorySegment wndClass = arena.allocate(Win32.WNDCLASSEXW);
            wndClass.set(JAVA_INT, 0, (int) Win32.WNDCLASSEXW.byteSize());
            wndClass.set(ADDRESS, 8, stub);
            wndClass.set(ADDRESS, 24, Win32.getModuleHandle());
            wndClass.set(ADDRESS, 64, WideStrings.alloc(arena, DISPATCH_CLASS));
            Win32.registerClassEx(wndClass);

            this.hwnd = Win32.createWindowEx(0,
                    WideStrings.alloc(arena, DISPATCH_CLASS),
                    WideStrings.alloc(arena, ""),
                    0, 0, 0, 0, 0,
                    MemorySegment.ofAddress(Win32.HWND_MESSAGE),
                    MemorySegment.NULL, Win32.getModuleHandle(), MemorySegment.NULL);
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Failed to create dispatcher message window");
            }
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused") // upcall
    long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        if (msg == Win32.WM_APP_DISPATCH) {
            drain();
            return 0;
        }
        return Win32.defWindowProc(hwnd, msg, wParam, lParam);
    }

    private void drain() {
        Runnable action;
        while ((action = queue.poll()) != null) {
            try {
                action.run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "UI task failed", e);
            }
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
            return;
        }
        queue.add(action);
        Win32.postMessage(hwnd, Win32.WM_APP_DISPATCH, 0, 0);
    }

    @Override
    public <T> CompletionStage<T> submit(Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        execute(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void assertUiThread() {
        if (!isUiThread()) {
            if (strictThreadChecks) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Not on the Win32 UI thread");
            }
            LOG.log(Level.ERROR, "UI-thread contract violated (production: failing safe)");
        }
    }

    /** UI-thread only; destroys the message window. The arena stays alive with the app. */
    void close() {
        Win32.destroyWindow(hwnd);
    }
}
