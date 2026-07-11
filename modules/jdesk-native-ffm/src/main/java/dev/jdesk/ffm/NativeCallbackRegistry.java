package dev.jdesk.ffm;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Strongly retains everything an upcall needs to stay alive: the Java target, its
 * method handle, the upcall stub segment, the owning shared arena, and the platform
 * registration token (spec section 6.2). Unregistration runs in reverse registration
 * order. After {@link #close()} the gate rejects late callbacks; the arena is closed
 * only when in-flight callbacks have drained, otherwise it is deliberately leaked and
 * logged (freeing under a live callback would be worse).
 */
public final class NativeCallbackRegistry implements AutoCloseable {
    private static final Logger LOG = System.getLogger(NativeCallbackRegistry.class.getName());
    private static final Duration QUIESCENCE_TIMEOUT = Duration.ofSeconds(10);

    /** One pinned callback registration. */
    public record Registration(
            String name,
            Object target,
            MethodHandle handle,
            MemorySegment stub,
            Object platformToken,
            Runnable detach) {

        public Registration {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(stub, "stub");
            Objects.requireNonNull(detach, "detach");
        }
    }

    private final String owner;
    private final Arena arena;
    private final CallbackGate gate = new CallbackGate();
    private final Deque<Registration> registrations = new ArrayDeque<>();
    private boolean closed;

    /**
     * @param owner diagnostic name of the owning component (e.g. a window id)
     * @param arena shared arena owning every stub registered here; closed by this
     *        registry and by nothing else
     */
    public NativeCallbackRegistry(String owner, Arena arena) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.arena = Objects.requireNonNull(arena, "arena");
    }

    /** Arena for allocating upcall stubs pinned by this registry. */
    public Arena arena() {
        return arena;
    }

    /** Gate that every upcall body must pass through. */
    public CallbackGate gate() {
        return gate;
    }

    public synchronized void register(Registration registration) {
        if (closed) {
            throw new JDeskException(ErrorCode.ALREADY_CLOSED,
                    "Callback registry " + owner + " is closed");
        }
        registrations.push(registration);
    }

    public synchronized int size() {
        return registrations.size();
    }

    /**
     * Detaches all callbacks in reverse registration order, waits for in-flight upcalls,
     * then closes the arena. Idempotent.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!registrations.isEmpty()) {
            Registration registration = registrations.pop();
            try {
                registration.detach().run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR,
                        "Detach failed for callback {0} of {1}; continuing with remaining detaches",
                        registration.name(), owner, e);
            }
        }
        boolean quiescent = gate.closeAndAwaitQuiescence(QUIESCENCE_TIMEOUT);
        if (quiescent) {
            arena.close();
        } else {
            LOG.log(Level.ERROR,
                    "Callback registry {0} still has in-flight upcalls after {1}; "
                            + "leaking arena instead of freeing memory under a live callback",
                    owner, QUIESCENCE_TIMEOUT);
        }
    }
}
