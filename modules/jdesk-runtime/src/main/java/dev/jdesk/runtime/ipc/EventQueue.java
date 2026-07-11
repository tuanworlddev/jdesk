package dev.jdesk.runtime.ipc;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bounded per-window event queue preserving enqueue order from one emitter. The sink
 * receives serialized envelopes in order; only one drain runs at a time so ordering
 * holds even with concurrent emitters.
 */
public final class EventQueue {
    private record QueuedEvent(String name, String envelopeJson) {
    }

    private final int capacity;
    private final EventOverflowPolicy policy;
    private final Function<String, CompletionStage<Void>> sink;
    private final Deque<QueuedEvent> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private QueuedEvent inFlight;
    private boolean draining;
    private boolean closed;
    private long droppedCount;

    public EventQueue(int capacity, EventOverflowPolicy policy,
            Function<String, CompletionStage<Void>> sink) {
        this.capacity = capacity;
        this.policy = policy;
        this.sink = sink;
    }

    /** Convenience adapter for synchronous sinks and focused unit tests. */
    public EventQueue(int capacity, EventOverflowPolicy policy, Consumer<String> sink) {
        this(capacity, policy, json -> {
            sink.accept(json);
            return CompletableFuture.completedFuture(null);
        });
    }

    public void enqueue(String eventName, String envelopeJson) {
        synchronized (lock) {
            if (closed) {
                throw new JDeskException(ErrorCode.WINDOW_CLOSED, "Event target window is closed");
            }
            int outstanding = queue.size() + (inFlight == null ? 0 : 1);
            if (outstanding >= capacity) {
                switch (policy) {
                    case REJECT -> throw new JDeskException(ErrorCode.LIMIT_EXCEEDED,
                            "Event queue is full");
                    case DROP_OLDEST -> {
                        queue.pollFirst();
                        droppedCount++;
                    }
                    case COALESCE -> {
                        if (!coalesce(eventName)) {
                            throw new JDeskException(ErrorCode.LIMIT_EXCEEDED,
                                    "Event queue is full and no event with this name to coalesce");
                        }
                    }
                }
            } else if (policy == EventOverflowPolicy.COALESCE) {
                coalesce(eventName);
            }
            queue.addLast(new QueuedEvent(eventName, envelopeJson));
        }
        startDrain();
    }

    private boolean coalesce(String eventName) {
        Iterator<QueuedEvent> it = queue.iterator();
        while (it.hasNext()) {
            if (it.next().name().equals(eventName)) {
                it.remove();
                droppedCount++;
                return true;
            }
        }
        return false;
    }

    private void startDrain() {
        synchronized (lock) {
            if (draining) {
                return;
            }
            draining = true;
        }
        drainReadyStages();
    }

    /**
     * Keeps the head queued until the sink confirms delivery. This makes capacity cover
     * work already submitted to an asynchronous UI dispatcher, not just work waiting to
     * be submitted.
     */
    private void drainReadyStages() {
        while (true) {
            QueuedEvent next;
            synchronized (lock) {
                next = queue.pollFirst();
                if (next == null || closed) {
                    draining = false;
                    return;
                }
                inFlight = next;
            }

            CompletionStage<Void> delivery;
            try {
                delivery = sink.apply(next.envelopeJson());
                if (delivery == null) {
                    delivery = CompletableFuture.failedFuture(
                            new IllegalStateException("Event sink returned no completion stage"));
                }
            } catch (RuntimeException e) {
                finishDelivery(next);
                synchronized (lock) {
                    draining = false;
                }
                throw e;
            }

            CompletableFuture<Void> future = delivery.toCompletableFuture();
            if (future.isDone()) {
                finishDelivery(next);
                continue;
            }
            future.whenComplete((ignored, failure) -> {
                finishDelivery(next);
                drainReadyStages();
            });
            return;
        }
    }

    private void finishDelivery(QueuedEvent delivered) {
        synchronized (lock) {
            if (inFlight == delivered) {
                inFlight = null;
            }
        }
    }

    public int pending() {
        synchronized (lock) {
            return queue.size() + (inFlight == null ? 0 : 1);
        }
    }

    public long dropped() {
        synchronized (lock) {
            return droppedCount;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            queue.clear();
            inFlight = null;
        }
    }
}
