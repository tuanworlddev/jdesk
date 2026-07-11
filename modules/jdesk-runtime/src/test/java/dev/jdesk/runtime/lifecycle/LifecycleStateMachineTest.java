package dev.jdesk.runtime.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.LifecycleState;
import dev.jdesk.api.WindowId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle state machine (spec sections 5 and 17.2: shutdown state machine).
 * Transitions are strictly forward; listener failures never break a transition.
 */
class LifecycleStateMachineTest {

    private static final WindowId MAIN = new WindowId("main");

    /** Records every callback in order. */
    private static final class RecordingListener implements LifecycleListener {
        final List<String> calls = new ArrayList<>();
        boolean allowClose = true;

        @Override
        public void onStarting() {
            calls.add("starting");
        }

        @Override
        public void onReady() {
            calls.add("ready");
        }

        @Override
        public boolean onCloseRequested(WindowId windowId) {
            calls.add("closeRequested:" + windowId);
            return allowClose;
        }

        @Override
        public void onStopping() {
            calls.add("stopping");
        }

        @Override
        public void onStopped() {
            calls.add("stopped");
        }
    }

    @Test
    void fullTransitionSequenceNotifiesListenersInOrder() {
        RecordingListener listener = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(listener));
        assertThat(machine.state()).isEqualTo(LifecycleState.NEW);

        machine.starting();
        assertThat(machine.state()).isEqualTo(LifecycleState.STARTING);
        machine.ready();
        assertThat(machine.state()).isEqualTo(LifecycleState.READY);
        machine.stopping();
        assertThat(machine.state()).isEqualTo(LifecycleState.STOPPING);
        machine.stopped();
        assertThat(machine.state()).isEqualTo(LifecycleState.STOPPED);

        assertThat(listener.calls).containsExactly("starting", "ready", "stopping", "stopped");
    }

    @Test
    void listenersAreNotifiedInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        LifecycleListener first = new LifecycleListener() {
            @Override
            public void onStarting() {
                order.add("first");
            }
        };
        LifecycleListener second = new LifecycleListener() {
            @Override
            public void onStarting() {
                order.add("second");
            }
        };
        new LifecycleStateMachine(List.of(first, second)).starting();
        assertThat(order).containsExactly("first", "second");
    }

    @Test
    void readyBeforeStartingIsIllegalState() {
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of());
        assertThatThrownBy(machine::ready)
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        assertThat(machine.state()).isEqualTo(LifecycleState.NEW);
    }

    @Test
    void stoppingFromNewIsIllegalState() {
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of());
        assertThatThrownBy(machine::stopping)
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }

    @Test
    void stoppedRequiresStopping() {
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of());
        machine.starting();
        machine.ready();
        assertThatThrownBy(machine::stopped)
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
        assertThat(machine.state()).isEqualTo(LifecycleState.READY);
    }

    @Test
    void stoppingIsIdempotent() {
        RecordingListener listener = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(listener));
        machine.starting();
        machine.ready();
        machine.stopping();
        machine.stopping(); // second call is a no-op
        assertThat(machine.state()).isEqualTo(LifecycleState.STOPPING);
        assertThat(listener.calls).containsExactly("starting", "ready", "stopping");
    }

    @Test
    void stoppingFromStartingIsAllowed() {
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of());
        machine.starting();
        machine.stopping();
        assertThat(machine.state()).isEqualTo(LifecycleState.STOPPING);
    }

    @Test
    void stoppedIsIdempotent() {
        RecordingListener listener = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(listener));
        machine.starting();
        machine.ready();
        machine.stopping();
        machine.stopped();
        machine.stopped(); // no-op, no second callback
        assertThat(machine.state()).isEqualTo(LifecycleState.STOPPED);
        assertThat(listener.calls).containsExactly("starting", "ready", "stopping", "stopped");
    }

    @Test
    void closeRequestedVetoedByOneListenerReturnsFalse() {
        RecordingListener vetoing = new RecordingListener();
        vetoing.allowClose = false;
        RecordingListener allowing = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(vetoing, allowing));
        machine.starting();
        machine.ready();
        assertThat(machine.closeRequested(MAIN)).isFalse();
        assertThat(machine.state()).isEqualTo(LifecycleState.READY);
    }

    @Test
    void closeRequestedAllowedWhenAllListenersAgree() {
        RecordingListener a = new RecordingListener();
        RecordingListener b = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(a, b));
        machine.starting();
        machine.ready();
        assertThat(machine.closeRequested(MAIN)).isTrue();
        assertThat(a.calls).contains("closeRequested:main");
        assertThat(b.calls).contains("closeRequested:main");
    }

    @Test
    void throwingCloseListenerIsSkippedNotAVeto() {
        LifecycleListener throwing = new LifecycleListener() {
            @Override
            public boolean onCloseRequested(WindowId windowId) {
                throw new IllegalStateException("listener bug");
            }
        };
        RecordingListener after = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(throwing, after));
        machine.starting();
        machine.ready();
        assertThat(machine.closeRequested(MAIN)).isTrue();
        assertThat(after.calls).contains("closeRequested:main");
    }

    @Test
    void closeRequestedOutsideReadyNeverBlocks() {
        RecordingListener vetoing = new RecordingListener();
        vetoing.allowClose = false;
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(vetoing));
        // NEW: shutdown paths must never be blocked.
        assertThat(machine.closeRequested(MAIN)).isTrue();
        machine.starting();
        assertThat(machine.closeRequested(MAIN)).isTrue();
        machine.ready();
        machine.stopping();
        assertThat(machine.closeRequested(MAIN)).isTrue();
        // The vetoing listener was never even consulted outside READY.
        assertThat(vetoing.calls).doesNotContain("closeRequested:main");
    }

    @Test
    void listenerExceptionsDoNotBreakTransitions() {
        LifecycleListener throwing = new LifecycleListener() {
            @Override
            public void onStarting() {
                throw new RuntimeException("boom");
            }

            @Override
            public void onStopping() {
                throw new RuntimeException("boom");
            }
        };
        RecordingListener recording = new RecordingListener();
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of(throwing, recording));
        machine.starting();
        assertThat(machine.state()).isEqualTo(LifecycleState.STARTING);
        machine.ready();
        machine.stopping();
        machine.stopped();
        assertThat(machine.state()).isEqualTo(LifecycleState.STOPPED);
        assertThat(recording.calls).containsExactly("starting", "ready", "stopping", "stopped");
    }

    @Test
    void recentTransitionsRecordsHistoryOldestFirst() {
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of());
        machine.starting();
        machine.ready();
        machine.closeRequested(MAIN);
        machine.stopping();
        machine.stopped();
        assertThat(machine.recentTransitions()).containsExactly(
                "-> STARTING", "-> READY", "closeRequested:main", "-> STOPPING", "-> STOPPED");
    }

    @Test
    void recentTransitionsIsBoundedAtSixteen() {
        LifecycleStateMachine machine = new LifecycleStateMachine(List.of());
        machine.starting();
        machine.ready();
        for (int i = 0; i < 30; i++) {
            machine.closeRequested(MAIN);
        }
        List<String> history = machine.recentTransitions();
        assertThat(history).hasSize(16);
        // Oldest entries (STARTING/READY) were evicted; only closeRequested labels remain.
        assertThat(history).allMatch(label -> label.equals("closeRequested:main"));
    }
}
