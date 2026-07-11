package dev.jdesk.runtime.lifecycle;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.LifecycleState;
import dev.jdesk.api.WindowId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Application lifecycle: {@code NEW -> STARTING -> READY -> STOPPING -> STOPPED},
 * strictly forward. Listener exceptions are logged and never break a transition.
 * Recent transitions are kept for crash diagnostics (spec section 13).
 */
public final class LifecycleStateMachine {
    private static final Logger LOG = System.getLogger(LifecycleStateMachine.class.getName());
    private static final int TRANSITION_HISTORY = 16;

    private final List<LifecycleListener> listeners;
    private final Deque<String> recentTransitions = new ArrayDeque<>();
    private LifecycleState state = LifecycleState.NEW;

    public LifecycleStateMachine(List<LifecycleListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    public synchronized LifecycleState state() {
        return state;
    }

    public synchronized void starting() {
        advance(LifecycleState.NEW, LifecycleState.STARTING);
        notifyListeners(LifecycleListener::onStarting);
    }

    public synchronized void ready() {
        ready(null);
    }

    public synchronized void ready(ApplicationHandle application) {
        advance(LifecycleState.STARTING, LifecycleState.READY);
        if (application == null) {
            notifyListeners(LifecycleListener::onReady);
        } else {
            notifyListeners(listener -> listener.onReady(application));
        }
    }

    /**
     * Polls listeners for a close veto. Not a state transition; the application stays
     * READY when any listener vetoes.
     *
     * @return true when the close may proceed
     */
    public synchronized boolean closeRequested(WindowId windowId) {
        if (state != LifecycleState.READY) {
            return true; // never block shutdown paths outside READY
        }
        record("closeRequested:" + windowId);
        for (LifecycleListener listener : listeners) {
            try {
                if (!listener.onCloseRequested(windowId)) {
                    return false;
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Lifecycle listener failed in onCloseRequested", e);
            }
        }
        return true;
    }

    public synchronized void stopping() {
        if (state == LifecycleState.STOPPING || state == LifecycleState.STOPPED) {
            return; // stop is idempotent
        }
        if (state != LifecycleState.STARTING && state != LifecycleState.READY) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Cannot stop from state " + state);
        }
        state = LifecycleState.STOPPING;
        record("-> STOPPING");
        notifyListeners(LifecycleListener::onStopping);
    }

    public synchronized void stopped() {
        if (state == LifecycleState.STOPPED) {
            return;
        }
        advance(LifecycleState.STOPPING, LifecycleState.STOPPED);
        notifyListeners(LifecycleListener::onStopped);
    }

    /** Recent transition labels, oldest first; safe for diagnostics output. */
    public synchronized List<String> recentTransitions() {
        return new ArrayList<>(recentTransitions);
    }

    private void advance(LifecycleState expected, LifecycleState next) {
        if (state != expected) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Cannot transition to " + next + " from " + state);
        }
        state = next;
        record("-> " + next);
    }

    private void record(String label) {
        if (recentTransitions.size() >= TRANSITION_HISTORY) {
            recentTransitions.pollFirst();
        }
        recentTransitions.addLast(label);
    }

    private void notifyListeners(java.util.function.Consumer<LifecycleListener> call) {
        for (LifecycleListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Lifecycle listener failed", e);
            }
        }
    }
}
