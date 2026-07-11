package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import org.junit.jupiter.api.Test;

/** Release-build CSP screening: unsafe allowances fail without explicit acknowledgement. */
class CspValidatorTest {

    @Test
    void defaultCspIsStrictAndPasses() {
        assertThat(CspValidator.DEFAULT_CSP)
                .doesNotContain("'unsafe-inline'")
                .doesNotContain("'unsafe-eval'")
                .doesNotContain("'unsafe-hashes'");
        assertThatCode(() -> CspValidator.validateForRelease(CspValidator.DEFAULT_CSP, false))
                .doesNotThrowAnyException();
    }

    @Test
    void unsafeInlineWithoutAcknowledgementIsRejected() {
        assertThatThrownBy(() -> CspValidator.validateForRelease(
                "script-src 'self' 'unsafe-inline'", false))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }

    @Test
    void unsafeEvalWithoutAcknowledgementIsRejected() {
        assertThatThrownBy(() -> CspValidator.validateForRelease(
                "script-src 'self' 'UNSAFE-EVAL'", false))
                .isInstanceOf(JDeskException.class);
    }

    @Test
    void unsafeHashesWithoutAcknowledgementIsRejected() {
        assertThatThrownBy(() -> CspValidator.validateForRelease(
                "script-src 'self' 'unsafe-hashes'", false))
                .isInstanceOf(JDeskException.class);
    }

    @Test
    void unsafeCspPassesWhenAcknowledged() {
        assertThatCode(() -> CspValidator.validateForRelease(
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'", true))
                .doesNotThrowAnyException();
    }
}
