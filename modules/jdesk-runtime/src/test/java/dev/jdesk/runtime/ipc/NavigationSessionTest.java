package dev.jdesk.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Navigation session nonce and request-id semantics (spec section 10.4). */
class NavigationSessionTest {

    @Test
    void nonceIs32LowercaseHexChars() {
        for (int i = 0; i < 50; i++) {
            String nonce = new NavigationSession().nonce();
            assertThat(nonce).hasSize(32).matches("[0-9a-f]{32}");
        }
    }

    @Test
    void noncesAreUniqueAcrossInstances() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(new NavigationSession().nonce()))
                    .as("nonce collision at instance %d", i)
                    .isTrue();
        }
    }

    @Test
    void acceptsOnlyExactNonceWhileLive() {
        NavigationSession session = new NavigationSession();
        assertThat(session.isInvalidated()).isFalse();
        assertThat(session.accepts(session.nonce())).isTrue();
        assertThat(session.accepts(session.nonce().toUpperCase())).isFalse();
        assertThat(session.accepts(session.nonce() + "0")).isFalse();
        assertThat(session.accepts("")).isFalse();
        assertThat(session.accepts(null)).isFalse();
        assertThat(session.accepts(new NavigationSession().nonce())).isFalse();
    }

    @Test
    void registerRequestIdEnforcesUniqueness() {
        NavigationSession session = new NavigationSession();
        assertThat(session.registerRequestId("req-1")).isTrue();
        assertThat(session.registerRequestId("req-1")).isFalse();
        assertThat(session.registerRequestId("req-2")).isTrue();
    }

    @Test
    void invalidateTurnsEverythingOff() {
        NavigationSession session = new NavigationSession();
        String nonce = session.nonce();
        assertThat(session.registerRequestId("before")).isTrue();
        session.invalidate();
        assertThat(session.isInvalidated()).isTrue();
        assertThat(session.accepts(nonce)).isFalse();
        assertThat(session.registerRequestId("after")).isFalse();
        assertThat(session.registerRequestId("before")).isFalse();
    }

    @Test
    void trackedRequestIdsAreCappedAndFailClosed() {
        NavigationSession session = new NavigationSession();
        for (int i = 0; i < NavigationSession.MAX_TRACKED_REQUEST_IDS; i++) {
            assertThat(session.registerRequestId("id-" + i)).isTrue();
        }
        // Cap reached: every further registration fails closed, even fresh ids.
        assertThat(session.registerRequestId("id-overflow-1")).isFalse();
        assertThat(session.registerRequestId("id-overflow-2")).isFalse();
        // The session itself is still live for already-known nonce checks.
        assertThat(session.accepts(session.nonce())).isTrue();
    }
}
