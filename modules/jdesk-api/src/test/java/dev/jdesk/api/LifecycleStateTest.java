package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Enum surface of {@link LifecycleState}. */
class LifecycleStateTest {

    @Test
    void valuesAreInStrictForwardOrder() {
        assertThat(LifecycleState.values()).containsExactly(
                LifecycleState.NEW,
                LifecycleState.STARTING,
                LifecycleState.READY,
                LifecycleState.STOPPING,
                LifecycleState.STOPPED);
    }

    @Test
    void valueOfRoundTrips() {
        for (LifecycleState state : LifecycleState.values()) {
            assertThat(LifecycleState.valueOf(state.name())).isEqualTo(state);
        }
    }
}
