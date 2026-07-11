package dev.jdesk.ffm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Tests the NEW -> OPEN -> CLOSING -> CLOSED state machine (spec section 6.3). */
class NativeHandleTest {

    /** Handle counting how often releaseNative runs and recording the state it saw. */
    private static class CountingHandle extends NativeHandle {
        final AtomicInteger releaseCount = new AtomicInteger();
        final AtomicReference<HandleState> stateDuringRelease = new AtomicReference<>();

        CountingHandle() {
            super("counting-handle");
        }

        @Override
        protected void releaseNative() {
            stateDuringRelease.set(state());
            releaseCount.incrementAndGet();
        }
    }

    @Test
    void followsNewOpenClosingClosedLifecycle() {
        CountingHandle handle = new CountingHandle();
        assertThat(handle.state()).isEqualTo(HandleState.NEW);

        handle.markOpen();
        assertThat(handle.state()).isEqualTo(HandleState.OPEN);

        handle.close();
        assertThat(handle.state()).isEqualTo(HandleState.CLOSED);
        // releaseNative must observe the transient CLOSING state.
        assertThat(handle.stateDuringRelease.get()).isEqualTo(HandleState.CLOSING);
        assertThat(handle.releaseCount.get()).isEqualTo(1);
    }

    @Test
    void requireOpenPassesWhileOpen() {
        CountingHandle handle = new CountingHandle();
        handle.markOpen();
        handle.requireOpen(); // must not throw
    }

    @Test
    void requireOpenThrowsBeforeOpen() {
        CountingHandle handle = new CountingHandle();
        JDeskException e = catchThrowableOfType(JDeskException.class, handle::requireOpen);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_CLOSED);
    }

    @Test
    void closeIsIdempotentAndReleasesExactlyOnce() {
        CountingHandle handle = new CountingHandle();
        handle.markOpen();
        handle.close();
        handle.close();
        handle.close();
        assertThat(handle.releaseCount.get()).isEqualTo(1);
        assertThat(handle.state()).isEqualTo(HandleState.CLOSED);
    }

    @Test
    void closeFromNewNeverCallsReleaseNative() {
        CountingHandle handle = new CountingHandle();
        handle.close();
        assertThat(handle.releaseCount.get()).isZero();
        assertThat(handle.state()).isEqualTo(HandleState.CLOSED);
        // still idempotent afterwards
        handle.close();
        assertThat(handle.releaseCount.get()).isZero();
    }

    @Test
    void requireOpenThrowsAlreadyClosedAfterClose() {
        CountingHandle handle = new CountingHandle();
        handle.markOpen();
        handle.close();

        JDeskException e = catchThrowableOfType(JDeskException.class, handle::requireOpen);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_CLOSED);
        assertThat(e.publicMessage()).contains("counting-handle");
    }

    @Test
    void markOpenTwiceThrowsIllegalState() {
        CountingHandle handle = new CountingHandle();
        handle.markOpen();

        JDeskException e = catchThrowableOfType(JDeskException.class, handle::markOpen);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE);
    }

    @Test
    void markOpenAfterCloseThrowsIllegalState() {
        CountingHandle handle = new CountingHandle();
        handle.close();

        JDeskException e = catchThrowableOfType(JDeskException.class, handle::markOpen);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE);
    }

    @Test
    void concurrentCloseReleasesExactlyOnce() throws Exception {
        final int threads = 8;
        for (int iteration = 0; iteration < 100; iteration++) {
            CountingHandle handle = new CountingHandle();
            handle.markOpen();

            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    handle.close();
                });
                worker.start();
                workers.add(worker);
            }
            for (Thread worker : workers) {
                worker.join(TimeUnit.SECONDS.toMillis(5));
                assertThat(worker.isAlive()).isFalse();
            }

            assertThat(handle.releaseCount.get())
                    .as("iteration %d: releaseNative must run exactly once", iteration)
                    .isEqualTo(1);
            assertThat(handle.state()).isEqualTo(HandleState.CLOSED);
        }
    }

    @Test
    void operationsFailWhileClosing() throws Exception {
        CountDownLatch releaseStarted = new CountDownLatch(1);
        CountDownLatch releaseMayFinish = new CountDownLatch(1);

        NativeHandle handle = new NativeHandle("blocking-handle") {
            @Override
            protected void releaseNative() {
                releaseStarted.countDown();
                try {
                    if (!releaseMayFinish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("release latch never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        };
        handle.markOpen();

        Thread closer = new Thread(handle::close);
        closer.start();
        try {
            assertThat(releaseStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handle.state()).isEqualTo(HandleState.CLOSING);

            assertThatThrownBy(handle::requireOpen)
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_CLOSED));
        } finally {
            releaseMayFinish.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertThat(closer.isAlive()).isFalse();
        assertThat(handle.state()).isEqualTo(HandleState.CLOSED);
    }

    @Test
    void toStringContainsDescriptionAndState() {
        CountingHandle handle = new CountingHandle();
        assertThat(handle.toString()).contains("counting-handle").contains("NEW");
        handle.markOpen();
        assertThat(handle.toString()).contains("OPEN");
    }
}
