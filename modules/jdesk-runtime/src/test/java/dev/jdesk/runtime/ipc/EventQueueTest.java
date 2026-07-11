package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Bounded event queue: FIFO delivery and overflow policies (spec section 10.5). */
class EventQueueTest {

    /**
     * Sink whose first accepted event blocks until released. Because {@code drain()} is
     * synchronous, blocking the sink from a background thread is the only way the queue
     * can actually fill: the drainer holds the {@code draining} flag while parked in the
     * sink, so events enqueued meanwhile pile up behind it.
     */
    private static final class BlockingSink implements Consumer<String> {
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean blockFirst = true;

        @Override
        public void accept(String json) {
            if (blockFirst) {
                blockFirst = false;
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("sink never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            delivered.add(json);
        }
    }

    /** Starts a background enqueue whose drain blocks in the sink; waits until parked. */
    private static Thread blockDrainer(EventQueue queue, BlockingSink sink) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try {
                queue.enqueue("blocker", "{\"e\":\"blocker\"}");
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "event-drainer");
        drainer.start();
        assertThat(sink.entered.await(5, TimeUnit.SECONDS)).as("sink entered").isTrue();
        assertThat(failure.get()).isNull();
        return drainer;
    }

    @Test
    void preservesFifoOrder() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        EventQueue queue = new EventQueue(16, EventOverflowPolicy.REJECT,
                (Consumer<String>) delivered::add);
        for (int i = 0; i < 10; i++) {
            queue.enqueue("tick", "{\"n\":" + i + "}");
        }
        assertThat(delivered).containsExactly(
                "{\"n\":0}", "{\"n\":1}", "{\"n\":2}", "{\"n\":3}", "{\"n\":4}",
                "{\"n\":5}", "{\"n\":6}", "{\"n\":7}", "{\"n\":8}", "{\"n\":9}");
        assertThat(queue.pending()).isZero();
        assertThat(queue.dropped()).isZero();
    }

    @Test
    @Timeout(10)
    void rejectPolicyThrowsLimitExceededWhenFull() throws Exception {
        BlockingSink sink = new BlockingSink();
        EventQueue queue = new EventQueue(4, EventOverflowPolicy.REJECT, sink);
        Thread drainer = blockDrainer(queue, sink);
        try {
            queue.enqueue("a", "{\"e\":\"a\"}");
            queue.enqueue("b", "{\"e\":\"b\"}");
            queue.enqueue("c", "{\"e\":\"c\"}");
            assertThat(queue.pending()).isEqualTo(4);

            assertThatThrownBy(() -> queue.enqueue("d", "{\"e\":\"d\"}"))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
            assertThat(queue.dropped()).isZero();
        } finally {
            sink.release.countDown();
            drainer.join(5_000);
        }
        // After release everything queued before the overflow drains in order.
        assertThat(sink.delivered).containsExactly(
                "{\"e\":\"blocker\"}", "{\"e\":\"a\"}", "{\"e\":\"b\"}", "{\"e\":\"c\"}");
    }

    @Test
    @Timeout(10)
    void dropOldestPolicyDropsHeadAndCounts() throws Exception {
        BlockingSink sink = new BlockingSink();
        EventQueue queue = new EventQueue(4, EventOverflowPolicy.DROP_OLDEST, sink);
        Thread drainer = blockDrainer(queue, sink);
        try {
            queue.enqueue("a", "{\"e\":\"a\"}");
            queue.enqueue("b", "{\"e\":\"b\"}");
            queue.enqueue("c", "{\"e\":\"c\"}");
            assertThat(queue.pending()).isEqualTo(4);

            queue.enqueue("d", "{\"e\":\"d\"}"); // drops oldest queued: "a"
            assertThat(queue.dropped()).isEqualTo(1);
            assertThat(queue.pending()).isEqualTo(4);
        } finally {
            sink.release.countDown();
            drainer.join(5_000);
        }
        assertThat(sink.delivered).containsExactly(
                "{\"e\":\"blocker\"}", "{\"e\":\"b\"}", "{\"e\":\"c\"}", "{\"e\":\"d\"}");
    }

    @Test
    @Timeout(10)
    void coalesceReplacesQueuedEventWithSameName() throws Exception {
        BlockingSink sink = new BlockingSink();
        EventQueue queue = new EventQueue(8, EventOverflowPolicy.COALESCE, sink);
        Thread drainer = blockDrainer(queue, sink);
        try {
            queue.enqueue("progress", "{\"pct\":10}");
            queue.enqueue("other", "{\"e\":\"other\"}");
            queue.enqueue("progress", "{\"pct\":50}"); // replaces queued pct:10
            assertThat(queue.pending()).isEqualTo(3);
            assertThat(queue.dropped()).isEqualTo(1);
        } finally {
            sink.release.countDown();
            drainer.join(5_000);
        }
        assertThat(sink.delivered).containsExactly(
                "{\"e\":\"blocker\"}", "{\"e\":\"other\"}", "{\"pct\":50}");
    }

    @Test
    @Timeout(10)
    void coalesceWhenFullWithNoSameNameThrowsLimitExceeded() throws Exception {
        BlockingSink sink = new BlockingSink();
        EventQueue queue = new EventQueue(3, EventOverflowPolicy.COALESCE, sink);
        Thread drainer = blockDrainer(queue, sink);
        try {
            queue.enqueue("a", "{\"e\":\"a\"}");
            queue.enqueue("b", "{\"e\":\"b\"}");
            assertThat(queue.pending()).isEqualTo(3);

            // Full, and "fresh" matches nothing queued: fail with LIMIT_EXCEEDED.
            assertThatThrownBy(() -> queue.enqueue("fresh", "{\"e\":\"fresh\"}"))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));

            // Full, but "b" is queued: coalesces instead of failing.
            queue.enqueue("b", "{\"e\":\"b2\"}");
            assertThat(queue.pending()).isEqualTo(3);
        } finally {
            sink.release.countDown();
            drainer.join(5_000);
        }
        assertThat(sink.delivered).containsExactly(
                "{\"e\":\"blocker\"}", "{\"e\":\"a\"}", "{\"e\":\"b2\"}");
    }

    @Test
    @Timeout(10)
    void closeClearsQueueAndRejectsFurtherEnqueues() throws Exception {
        BlockingSink sink = new BlockingSink();
        EventQueue queue = new EventQueue(8, EventOverflowPolicy.REJECT, sink);
        Thread drainer = blockDrainer(queue, sink);
        try {
            queue.enqueue("a", "{\"e\":\"a\"}");
            queue.enqueue("b", "{\"e\":\"b\"}");
            assertThat(queue.pending()).isEqualTo(3);

            queue.close();
            assertThat(queue.pending()).isZero();
            assertThatThrownBy(() -> queue.enqueue("c", "{\"e\":\"c\"}"))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.WINDOW_CLOSED));
        } finally {
            sink.release.countDown();
            drainer.join(5_000);
        }
        // Nothing queued at close time may still be delivered afterwards.
        assertThat(sink.delivered).containsExactly("{\"e\":\"blocker\"}");
    }

    @Test
    @Timeout(20)
    void concurrentEmittersDeliverEveryEventWithoutErrors() throws Exception {
        final int emitters = 4;
        final int perEmitter = 50;
        AtomicInteger deliveredCount = new AtomicInteger();
        EventQueue queue = new EventQueue(256, EventOverflowPolicy.REJECT,
                (Consumer<String>) json -> deliveredCount.incrementAndGet());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(emitters);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < emitters; t++) {
            final int emitter = t;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perEmitter; i++) {
                        queue.enqueue("e" + emitter, "{\"emitter\":" + emitter + ",\"n\":" + i + "}");
                    }
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(deliveredCount.get()).isEqualTo(emitters * perEmitter);
        assertThat(queue.pending()).isZero();
    }

    @Test
    void asynchronousDeliveryRemainsCountedAgainstCapacityUntilCompletion() {
        CompletableFuture<Void> delivered = new CompletableFuture<>();
        EventQueue queue = new EventQueue(1, EventOverflowPolicy.REJECT,
                (Function<String, CompletionStage<Void>>) json -> delivered);

        queue.enqueue("first", "{\"n\":1}");
        assertThat(queue.pending()).isEqualTo(1);
        assertThatThrownBy(() -> queue.enqueue("second", "{\"n\":2}"))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));

        delivered.complete(null);
        assertThat(queue.pending()).isZero();
    }
}
