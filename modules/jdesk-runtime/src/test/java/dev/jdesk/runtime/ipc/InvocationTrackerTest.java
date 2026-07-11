package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** In-flight limit, cancellation, and exactly-once termination (spec sections 10.3/10.4). */
class InvocationTrackerTest {

    @Test
    void tryBeginUpToLimitThenNull() {
        InvocationTracker tracker = new InvocationTracker(3);
        NavigationSession session = new NavigationSession();
        assertThat(tracker.tryBegin("a", session)).isNotNull();
        assertThat(tracker.tryBegin("b", session)).isNotNull();
        assertThat(tracker.tryBegin("c", session)).isNotNull();
        assertThat(tracker.tryBegin("d", session)).isNull();
        assertThat(tracker.pending()).isEqualTo(3);
    }

    @Test
    void duplicateIdReturnsNull() {
        InvocationTracker tracker = new InvocationTracker(10);
        NavigationSession session = new NavigationSession();
        assertThat(tracker.tryBegin("same", session)).isNotNull();
        assertThat(tracker.tryBegin("same", session)).isNull();
        assertThat(tracker.pending()).isEqualTo(1);
    }

    @Test
    void removeFreesASlot() {
        InvocationTracker tracker = new InvocationTracker(1);
        NavigationSession session = new NavigationSession();
        assertThat(tracker.tryBegin("a", session)).isNotNull();
        assertThat(tracker.tryBegin("b", session)).isNull();
        tracker.remove("a");
        assertThat(tracker.pending()).isZero();
        assertThat(tracker.tryBegin("b", session)).isNotNull();
        // The removed id may be reused at the tracker level (session uniqueness is separate).
        tracker.remove("b");
        assertThat(tracker.tryBegin("a", session)).isNotNull();
    }

    @Test
    void findReturnsInFlightInvocation() {
        InvocationTracker tracker = new InvocationTracker(2);
        NavigationSession session = new NavigationSession();
        InvocationTracker.Invocation invocation = tracker.tryBegin("a", session);
        assertThat(tracker.find("a")).isSameAs(invocation);
        assertThat(tracker.find("missing")).isNull();
        assertThat(invocation.id()).isEqualTo("a");
        assertThat(invocation.session()).isSameAs(session);
    }

    @Test
    @Timeout(10)
    void cancelInterruptsBoundWorkerThread() throws Exception {
        InvocationTracker tracker = new InvocationTracker(4);
        InvocationTracker.Invocation invocation = tracker.tryBegin("sleepy", new NavigationSession());
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch bound = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            invocation.bindWorker(Thread.currentThread());
            bound.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }, "test-worker");
        worker.start();
        assertThat(bound.await(5, TimeUnit.SECONDS)).isTrue();

        InvocationTracker.Invocation cancelled = tracker.cancel("sleepy");
        assertThat(cancelled).isSameAs(invocation);

        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
        assertThat(invocation.isCancelled()).isTrue();
    }

    @Test
    void cancelUnknownIdReturnsNull() {
        InvocationTracker tracker = new InvocationTracker(2);
        assertThat(tracker.cancel("nope")).isNull();
    }

    @Test
    @Timeout(60)
    void tryTerminateWinsExactlyOnceUnderConcurrency() throws Exception {
        final int threads = 8;
        for (int iteration = 0; iteration < 200; iteration++) {
            InvocationTracker tracker = new InvocationTracker(1);
            InvocationTracker.Invocation invocation =
                    tracker.tryBegin("race-" + iteration, new NavigationSession());
            AtomicInteger wins = new AtomicInteger();
            CyclicBarrier barrier = new CyclicBarrier(threads);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        if (invocation.tryTerminate()) {
                            wins.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(wins.get()).as("iteration %d", iteration).isEqualTo(1);
            // And it never becomes claimable again.
            assertThat(invocation.tryTerminate()).isFalse();
        }
    }

    @Test
    void cancelSessionOnlyCancelsMatchingSessionsInvocations() {
        InvocationTracker tracker = new InvocationTracker(8);
        NavigationSession oldSession = new NavigationSession();
        NavigationSession newSession = new NavigationSession();
        InvocationTracker.Invocation oldA = tracker.tryBegin("old-a", oldSession);
        InvocationTracker.Invocation oldB = tracker.tryBegin("old-b", oldSession);
        InvocationTracker.Invocation fresh = tracker.tryBegin("new-a", newSession);

        var cancelled = tracker.cancelSession(oldSession);

        assertThat(cancelled).containsExactlyInAnyOrder(oldA, oldB);
        assertThat(oldA.isCancelled()).isTrue();
        assertThat(oldB.isCancelled()).isTrue();
        assertThat(fresh.isCancelled()).isFalse();
        // cancelSession does not itself remove them from tracking.
        assertThat(tracker.pending()).isEqualTo(3);
    }

    @Test
    void cancelAllCancelsEverything() {
        InvocationTracker tracker = new InvocationTracker(4);
        InvocationTracker.Invocation a = tracker.tryBegin("a", new NavigationSession());
        InvocationTracker.Invocation b = tracker.tryBegin("b", new NavigationSession());
        var cancelled = tracker.cancelAll();
        assertThat(cancelled).containsExactlyInAnyOrder(a, b);
        assertThat(a.isCancelled()).isTrue();
        assertThat(b.isCancelled()).isTrue();
    }

    @Test
    void pendingCountsInFlight() {
        InvocationTracker tracker = new InvocationTracker(10);
        NavigationSession session = new NavigationSession();
        assertThat(tracker.pending()).isZero();
        tracker.tryBegin("a", session);
        assertThat(tracker.pending()).isEqualTo(1);
        tracker.tryBegin("b", session);
        assertThat(tracker.pending()).isEqualTo(2);
        tracker.remove("a");
        assertThat(tracker.pending()).isEqualTo(1);
        tracker.remove("b");
        assertThat(tracker.pending()).isZero();
    }
}
