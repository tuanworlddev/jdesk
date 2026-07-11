package dev.jdesk.testapps.securityprobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.assets.CspValidator;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec 17.6 (final item) / 12.4 as a UNIT test: unsafe CSP configuration is surfaced in
 * build output. Release CSP validation must reject unsafe-inline/eval/hashes unless the
 * developer explicitly acknowledges the weakened policy through the named build option,
 * and the framework's default CSP must be strict. This is category "unit" — it exercises
 * pure security-decision logic and is intentionally NOT presented as native evidence.
 */
final class CspValidatorReleaseTest {

    @Test
    @DisplayName("the framework default CSP is strict (no unsafe-inline/eval/hashes)")
    void defaultCspIsStrict() {
        String csp = CspValidator.DEFAULT_CSP.toLowerCase(Locale.ROOT);
        assertThat(csp)
                .doesNotContain("'unsafe-inline'")
                .doesNotContain("'unsafe-eval'")
                .doesNotContain("'unsafe-hashes'");
        assertThat(csp).contains("default-src 'self'");
        // The strict default must itself pass release validation without acknowledgement.
        assertThatCode(() -> CspValidator.validateForRelease(CspValidator.DEFAULT_CSP, false))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "default-src 'self'; script-src 'self' 'unsafe-inline'",
            "default-src 'self'; script-src 'unsafe-eval'",
            "default-src 'self'; style-src 'unsafe-hashes'"
    })
    @DisplayName("unsafe CSP is rejected at release without explicit acknowledgement")
    void unsafeCspRejectedWithoutAck(String unsafeCsp) {
        assertThatThrownBy(() -> CspValidator.validateForRelease(unsafeCsp, false))
                .isInstanceOf(JDeskException.class)
                .hasMessageContaining("acknowledge");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "default-src 'self'; script-src 'self' 'unsafe-inline'",
            "default-src 'self'; script-src 'unsafe-eval'",
            "default-src 'self'; style-src 'unsafe-hashes'"
    })
    @DisplayName("unsafe CSP is accepted only with explicit acknowledgement")
    void unsafeCspAcceptedWithAck(String unsafeCsp) {
        assertThatCode(() -> CspValidator.validateForRelease(unsafeCsp, true))
                .doesNotThrowAnyException();
    }
}
