package dev.jdesk.ffm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.ffm.NativeCallbackRegistry.Registration;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests callback pinning, reverse-order detach, and arena ownership (spec section 6.2). */
class NativeCallbackRegistryTest {

    private static final MethodHandle TRIVIAL_HANDLE = MethodHandles.constant(int.class, 42);
    private static final MemorySegment STUB = MemorySegment.ofArray(new byte[8]);

    private static Registration registration(String name, Runnable detach) {
        return new Registration(name, new Object(), TRIVIAL_HANDLE, STUB, null, detach);
    }

    @Test
    void detachesInReverseRegistrationOrder() {
        Arena arena = Arena.ofShared();
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        List<String> detachOrder = new ArrayList<>();

        registry.register(registration("first", () -> detachOrder.add("first")));
        registry.register(registration("second", () -> detachOrder.add("second")));
        registry.register(registration("third", () -> detachOrder.add("third")));
        assertThat(registry.size()).isEqualTo(3);

        registry.close();

        assertThat(detachOrder).containsExactly("third", "second", "first");
        assertThat(registry.size()).isZero();
    }

    @Test
    void detachExceptionInMiddleDoesNotStopRemainingDetaches() {
        Arena arena = Arena.ofShared();
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        List<String> detachOrder = new ArrayList<>();

        registry.register(registration("first", () -> detachOrder.add("first")));
        registry.register(registration("boom", () -> {
            detachOrder.add("boom");
            throw new RuntimeException("detach failed deliberately");
        }));
        registry.register(registration("third", () -> detachOrder.add("third")));

        registry.close(); // must not propagate the detach exception

        assertThat(detachOrder).containsExactly("third", "boom", "first");
        assertThat(arena.scope().isAlive()).isFalse();
    }

    @Test
    void registerAfterCloseThrowsAlreadyClosed() {
        Arena arena = Arena.ofShared();
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        registry.close();

        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> registry.register(registration("late", () -> { })));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_CLOSED);
        assertThat(e.publicMessage()).contains("owner");
    }

    @Test
    void closeIsIdempotentAndDetachesOnlyOnce() {
        Arena arena = Arena.ofShared();
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        AtomicInteger detaches = new AtomicInteger();

        registry.register(registration("only", detaches::incrementAndGet));

        registry.close();
        registry.close(); // second close must be a no-op (and must not re-close the arena)
        registry.close();

        assertThat(detaches.get()).isEqualTo(1);
        assertThat(arena.scope().isAlive()).isFalse();
    }

    @Test
    void arenaIsClosedAfterCloseWhenQuiescent() {
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(16);
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        registry.register(registration("cb", () -> { }));

        assertThat(segment.scope().isAlive()).isTrue();

        registry.close();

        assertThat(segment.scope().isAlive()).isFalse();
        assertThat(arena.scope().isAlive()).isFalse();
        assertThat(registry.gate().isClosed()).isTrue();
        assertThat(registry.gate().enter()).isFalse(); // late callbacks are rejected
    }

    @Test
    @Tag("slow")
    void arenaIsLeakedNotClosedWhenCallbackNeverDrains() throws Exception {
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(16);
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        registry.register(registration("stuck", () -> { }));

        // Simulate an upcall that never returns: hold the gate open from another thread.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch mayExit = new CountDownLatch(1);
        Thread stuckCallback = new Thread(() -> {
            assertThat(registry.gate().enter()).isTrue();
            entered.countDown();
            try {
                mayExit.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            registry.gate().exit();
        });
        stuckCallback.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // close() blocks for the fixed 10 s QUIESCENCE_TIMEOUT, then leaks the arena.
        long start = System.nanoTime();
        registry.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis)
                .as("close must wait the full quiescence timeout before giving up")
                .isGreaterThanOrEqualTo(9_500);
        assertThat(arena.scope().isAlive())
                .as("arena must be leaked, never freed under a live callback")
                .isTrue();
        assertThat(segment.scope().isAlive()).isTrue();

        mayExit.countDown();
        stuckCallback.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(stuckCallback.isAlive()).isFalse();
        // The arena stays leaked by design even after the straggler finally exits.
        assertThat(arena.scope().isAlive()).isTrue();
    }

    @Test
    void registrationRejectsNullComponents() {
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new Registration(null, new Object(), TRIVIAL_HANDLE, STUB, null, () -> { })))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new Registration("n", null, TRIVIAL_HANDLE, STUB, null, () -> { })))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new Registration("n", new Object(), null, STUB, null, () -> { })))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new Registration("n", new Object(), TRIVIAL_HANDLE, null, null, () -> { })))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new Registration("n", new Object(), TRIVIAL_HANDLE, STUB, null, null)))
                .isNotNull();
    }

    @Test
    void exposesArenaAndGateAccessors() {
        Arena arena = Arena.ofShared();
        NativeCallbackRegistry registry = new NativeCallbackRegistry("owner", arena);
        assertThat(registry.arena()).isSameAs(arena);
        assertThat(registry.gate()).isNotNull();
        assertThat(registry.gate().isClosed()).isFalse();
        registry.close();
    }
}
