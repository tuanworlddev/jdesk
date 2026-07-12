package dev.jdesk.ffm;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Guards native upcalls against use-after-free. Every upcall body runs inside
 * {@link #enter()}/{@link #exit()}; {@link #closeAndAwaitQuiescence(Duration)} stops new
 * entries and waits for in-flight callbacks to drain before the owner may detach native
 * registrations and close the arena. A callback arriving after closure is rejected
 * safely ({@code enter()} returns false) instead of touching freed memory.
 */
public final class CallbackGate {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int inFlight;
    private boolean closed;

    public CallbackGate() {
    }

    /** @return true when the caller may proceed; false when the gate is closed. */
    public boolean enter() {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            inFlight++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void exit() {
        lock.lock();
        try {
            if (inFlight <= 0) {
                throw new IllegalStateException("exit() without matching enter()");
            }
            inFlight--;
            if (inFlight == 0) {
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public int inFlight() {
        lock.lock();
        try {
            return inFlight;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the gate and waits for in-flight callbacks to finish.
     *
     * @return true when quiescent within the timeout; false on timeout (the caller must
     *         then leak the arena rather than free memory under a live callback)
     */
    public boolean closeAndAwaitQuiescence(Duration timeout) {
        lock.lock();
        try {
            closed = true;
            long nanos = timeout.toNanos();
            while (inFlight > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = idle.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }
}
