package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

/** Structured exception: code, public message, cause. */
class JDeskExceptionTest {

    @Test
    void messageContainsCodeAndPublicMessage() {
        JDeskException e = new JDeskException(ErrorCode.INVALID_REQUEST, "Bad payload");

        assertThat(e.getMessage()).isEqualTo("INVALID_REQUEST: Bad payload");
        assertThat(e.getMessage()).contains(ErrorCode.INVALID_REQUEST.name());
        assertThat(e.getMessage()).contains("Bad payload");
    }

    @Test
    void accessorsExposeCodeAndPublicMessage() {
        JDeskException e = new JDeskException(ErrorCode.TIMEOUT, "Command timed out");

        assertThat(e.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(e.publicMessage()).isEqualTo("Command timed out");
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void causeIsPreserved() {
        IllegalArgumentException cause = new IllegalArgumentException("internal detail");
        JDeskException e = new JDeskException(ErrorCode.INTERNAL_ERROR, "Something failed", cause);

        assertThat(e.getCause()).isSameAs(cause);
        assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        // The public message must not automatically pick up internal cause detail.
        assertThat(e.publicMessage()).isEqualTo("Something failed");
    }

    @Test
    void twoArgConstructorHasNoCause() {
        JDeskException e = new JDeskException(ErrorCode.CANCELLED, "Cancelled");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void nullCodeOrMessageRejected() {
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new JDeskException(null, "msg"))).isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new JDeskException(ErrorCode.INTERNAL_ERROR, null))).isNotNull();
    }
}
