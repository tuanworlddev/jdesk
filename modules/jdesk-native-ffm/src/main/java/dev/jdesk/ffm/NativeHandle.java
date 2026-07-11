package dev.jdesk.ffm;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for native handle wrappers. Enforces {@code NEW -> OPEN -> CLOSING ->
 * CLOSED}: close is idempotent, operations after {@code CLOSING} fail with
 * {@link ErrorCode#ALREADY_CLOSED}, and release runs exactly once. Cleaner/finalizer
 * release is intentionally absent — owners close explicitly.
 */
public abstract class NativeHandle implements AutoCloseable {
    private final AtomicReference<HandleState> state = new AtomicReference<>(HandleState.NEW);
    private final String description;

    protected NativeHandle(String description) {
        this.description = description;
    }

    public final HandleState state() {
        return state.get();
    }

    /** Transition NEW -> OPEN after the native resource has been acquired. */
    protected final void markOpen() {
        if (!state.compareAndSet(HandleState.NEW, HandleState.OPEN)) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    description + " cannot open from state " + state.get());
        }
    }

    /** Guards every native operation; throws unless the handle is OPEN. */
    protected final void requireOpen() {
        HandleState current = state.get();
        if (current != HandleState.OPEN) {
            throw new JDeskException(ErrorCode.ALREADY_CLOSED,
                    description + " is not open (state " + current + ")");
        }
    }

    /** Releases the native resource. Runs exactly once, from {@link #close()}. */
    protected abstract void releaseNative();

    @Override
    public final void close() {
        if (state.compareAndSet(HandleState.NEW, HandleState.CLOSED)) {
            return; // never opened, nothing to release
        }
        if (!state.compareAndSet(HandleState.OPEN, HandleState.CLOSING)) {
            return; // already CLOSING or CLOSED: close is idempotent
        }
        try {
            releaseNative();
        } finally {
            state.set(HandleState.CLOSED);
        }
    }

    @Override
    public String toString() {
        return description + "[" + state.get() + "]";
    }
}
