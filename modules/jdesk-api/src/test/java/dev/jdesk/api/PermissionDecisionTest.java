package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

/** Permission decision value semantics. */
class PermissionDecisionTest {

    @Test
    void allowReturnsSharedSingleton() {
        PermissionDecision first = PermissionDecision.allow();
        PermissionDecision second = PermissionDecision.allow();

        assertThat(first).isSameAs(second);
        assertThat(first.allowed()).isTrue();
        assertThat(first.publicReason()).isEqualTo("allowed");
        assertThat(first.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void denyCarriesCodeAndPublicReason() {
        PermissionDecision decision =
                PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, "Not permitted");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.CAPABILITY_DENIED);
        assertThat(decision.publicReason()).isEqualTo("Not permitted");
    }

    @Test
    void denyDecisionsAreValueEqual() {
        assertThat(PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, "r"))
                .isEqualTo(PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, "r"));
        assertThat(PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, "r"))
                .isNotEqualTo(PermissionDecision.deny(ErrorCode.INVALID_REQUEST, "r"));
    }

    @Test
    void nullComponentsRejected() {
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new PermissionDecision(false, null, "reason"))).isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new PermissionDecision(false, ErrorCode.CAPABILITY_DENIED, null)))
                .isNotNull();
    }
}
