package dev.jdesk.runtime.ipc;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Consumer;

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
    private final Consumer<String> sink;
    private final Deque<QueuedEvent> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private boolean draining;
    private boolean closed;
    private long droppedCount;

    public EventQueue(int capacity, EventOverflowPolicy policy, Consumer<String> sink) {
        this.capacity = capacity;
        this.policy = policy;
        this.sink = sink;
    }

    public void enqueue(String eventName, String envelopeJson) {
        synchronized (lock) {
            if (closed) {
                throw new JDeskException(ErrorCode.WINDOW_CLOSED, "Event target window is closed");
            }
            if (queue.size() >= capacity) {
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
        drain();
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

    private void drain() {
        synchronized (lock) {
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            while (true) {
                QueuedEvent next;
                synchronized (lock) {
                    next = queue.pollFirst();
                    if (next == null || closed) {
                        draining = false;
                        return;
                    }
                }
                sink.accept(next.envelopeJson());
            }
        } catch (RuntimeException e) {
            synchronized (lock) {
                draining = false;
            }
            throw e;
        }
    }

    public int pending() {
        synchronized (lock) {
            return queue.size();
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
        }
    }
}
