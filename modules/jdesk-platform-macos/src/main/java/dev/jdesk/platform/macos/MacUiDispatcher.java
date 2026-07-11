package dev.jdesk.platform.macos;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.UiDispatcher;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
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
 * Marshals work onto the AppKit main thread through libdispatch: {@link #execute}
 * enqueues the runnable and {@code dispatch_async_f}s a trampoline onto the main queue
 * ({@code _dispatch_main_q}, the public symbol behind {@code dispatch_get_main_queue()}
 * in {@code dispatch/queue.h}); the main run loop drains the queue while
 * {@code [NSApp run]} is active. {@code isUiThread} uses {@code pthread_main_np}
 * (public libSystem API). No Objective-C blocks are needed for dispatch.
 */
final class MacUiDispatcher implements UiDispatcher {
    private static final Logger LOG = System.getLogger(MacUiDispatcher.class.getName());

    /**
     * Process-lifetime arena for the dispatch trampoline stub: a dispatch item may still
     * be queued at application teardown, so the stub must never be freed.
     */
    private static final Arena DISPATCH_ARENA = Arena.ofShared();
    private static final MemorySegment MAIN_QUEUE = ObjC.SYSTEM.findOrThrow("_dispatch_main_q");
    private static final MethodHandle DISPATCH_ASYNC_F = ObjC.LINKER.downcallHandle(
            ObjC.SYSTEM.findOrThrow("dispatch_async_f"),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle PTHREAD_MAIN_NP = ObjC.LINKER.downcallHandle(
            ObjC.SYSTEM.findOrThrow("pthread_main_np"),
            FunctionDescriptor.of(JAVA_INT));

    private final Thread uiThread;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final MemorySegment trampoline;
    private final boolean strictThreadChecks;

    /** Must be constructed on the process main thread. */
    MacUiDispatcher(boolean strictThreadChecks) {
        this.uiThread = Thread.currentThread();
        this.strictThreadChecks = strictThreadChecks;
        try {
            MethodHandle drain = MethodHandles.lookup().findVirtual(MacUiDispatcher.class,
                    "drain", MethodType.methodType(void.class, MemorySegment.class)).bindTo(this);
            this.trampoline = ObjC.LINKER.upcallStub(drain,
                    FunctionDescriptor.ofVoid(ADDRESS), DISPATCH_ARENA);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean onProcessMainThread() {
        try {
            return (int) PTHREAD_MAIN_NP.invokeExact() != 0;
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }

    @SuppressWarnings("unused") // dispatch_async_f upcall
    void drain(MemorySegment context) {
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            Runnable action;
            while ((action = queue.poll()) != null) {
                try {
                    action.run();
                } catch (Throwable t) {
                    LOG.log(Level.ERROR, "UI task failed", t);
                }
            }
        } finally {
            ObjC.autoreleasePoolPop(pool);
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
        postAsync(action);
    }

    /** Always enqueues, even from the main thread (used for deferred work). */
    void postAsync(Runnable action) {
        queue.add(action);
        try {
            DISPATCH_ASYNC_F.invokeExact(MAIN_QUEUE, MemorySegment.NULL, trampoline);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
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
                        "Not on the AppKit main thread");
            }
            LOG.log(Level.ERROR, "UI-thread contract violated (production: failing safe)");
        }
    }
}
