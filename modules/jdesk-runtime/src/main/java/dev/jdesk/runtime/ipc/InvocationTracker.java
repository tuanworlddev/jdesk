package dev.jdesk.runtime.ipc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks in-flight invocations for one window: enforces the in-flight limit, supports
 * best-effort cancellation by interrupting the worker virtual thread, and guarantees
 * exactly one terminal result per request via {@link Invocation#tryTerminate()}.
 */
public final class InvocationTracker {
    /** One in-flight invocation. */
    public static final class Invocation {
        private final String id;
        private final NavigationSession session;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Thread worker;
        private volatile boolean cancelled;

        Invocation(String id, NavigationSession session) {
            this.id = id;
            this.session = session;
        }

        public String id() {
            return id;
        }

        public NavigationSession session() {
            return session;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void bindWorker(Thread thread) {
            this.worker = thread;
        }

        /** True exactly once; every result-sending path must win this CAS first. */
        public boolean tryTerminate() {
            return terminal.compareAndSet(false, true);
        }

        void cancel() {
            cancelled = true;
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private final int maxInFlight;
    private final Map<String, Invocation> inFlight = new ConcurrentHashMap<>();

    public InvocationTracker(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    /**
     * @return the new invocation, or null when the in-flight limit is reached (the
     *         request must fail with LIMIT_EXCEEDED and never execute user code)
     */
    public Invocation tryBegin(String id, NavigationSession session) {
        synchronized (inFlight) {
            if (inFlight.size() >= maxInFlight) {
                return null;
            }
            Invocation invocation = new Invocation(id, session);
            return inFlight.putIfAbsent(id, invocation) == null ? invocation : null;
        }
    }

    public void remove(String id) {
        inFlight.remove(id);
    }

    public Invocation find(String id) {
        return inFlight.get(id);
    }

    /** Best-effort cancel; the terminal CANCELLED result is sent by the dispatcher. */
    public Invocation cancel(String id) {
        Invocation invocation = inFlight.get(id);
        if (invocation != null) {
            invocation.cancel();
        }
        return invocation;
    }

    public List<Invocation> cancelAll() {
        List<Invocation> cancelled = new ArrayList<>();
        for (Invocation invocation : inFlight.values()) {
            invocation.cancel();
            cancelled.add(invocation);
        }
        return cancelled;
    }

    /** Cancels only invocations belonging to {@code session}. */
    public List<Invocation> cancelSession(NavigationSession session) {
        List<Invocation> cancelled = new ArrayList<>();
        for (Invocation invocation : inFlight.values()) {
            if (invocation.session() == session) {
                invocation.cancel();
                cancelled.add(invocation);
            }
        }
        return cancelled;
    }

    public int pending() {
        return inFlight.size();
    }
}
