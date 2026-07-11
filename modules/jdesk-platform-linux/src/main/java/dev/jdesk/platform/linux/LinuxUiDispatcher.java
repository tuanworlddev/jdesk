package dev.jdesk.platform.linux;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.UiDispatcher;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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
 * Marshals work onto the GTK main thread (spec section 7: GTK and WebKitGTK run on
 * their GLib main context). The UI thread is the thread that ran
 * {@code gtk_init_check} and runs {@code gtk_main}. {@link #execute} from other threads
 * enqueues the runnable and posts a one-shot {@code GSourceFunc} trampoline onto the
 * default main context via {@code g_main_context_invoke_full} (a documented
 * thread-safe GLib API); the trampoline drains the queue on the main thread and
 * returns {@code G_SOURCE_REMOVE}.
 */
final class LinuxUiDispatcher implements UiDispatcher {
    private static final Logger LOG = System.getLogger(LinuxUiDispatcher.class.getName());

    private final Thread uiThread;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    /** Trampoline stub in the process-lifetime callback arena: a queued GSource may
     * still be pending at application teardown, so the stub must never be freed. */
    private final MemorySegment trampoline;
    private final boolean strictThreadChecks;

    /** Must be constructed on the thread that ran {@code gtk_init_check}. */
    LinuxUiDispatcher(boolean strictThreadChecks) {
        this.uiThread = Thread.currentThread();
        this.strictThreadChecks = strictThreadChecks;
        try {
            MethodHandle drain = MethodHandles.lookup().findVirtual(LinuxUiDispatcher.class,
                    "drain", MethodType.methodType(int.class, MemorySegment.class)).bindTo(this);
            this.trampoline = Gtk.upcall(drain, FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused") // GSourceFunc upcall
    int drain(MemorySegment userData) {
        Runnable action;
        while ((action = queue.poll()) != null) {
            try {
                action.run();
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "UI task failed", t);
            }
        }
        return Gtk.G_SOURCE_REMOVE;
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

    /** Always enqueues, even from the UI thread (used for deferred work). */
    void postAsync(Runnable action) {
        queue.add(action);
        try {
            Gtk.G_MAIN_CONTEXT_INVOKE_FULL.invokeExact(MemorySegment.NULL,
                    Gtk.G_PRIORITY_DEFAULT, trampoline, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
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
                        "Not on the GTK main thread");
            }
            LOG.log(Level.ERROR, "UI-thread contract violated (production: failing safe)");
        }
    }
}
