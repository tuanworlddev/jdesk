package dev.jdesk.runtime.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Spec section 11: JSON bounds may be lowered but never raised above the defaults. */
class JsonLimitsTest {

    @Test
    void defaultsMatchSpecSection11() {
        assertThat(JsonLimits.DEFAULTS.maxNestingDepth()).isEqualTo(64);
        assertThat(JsonLimits.DEFAULTS.maxStringLength()).isEqualTo(262_144);
        assertThat(JsonLimits.DEFAULTS.maxNumberLength()).isEqualTo(100);
        assertThat(JsonLimits.DEFAULTS.maxTotalBytes()).isEqualTo(1_048_576);
    }

    @Test
    void loweringEveryFieldIsAllowed() {
        assertThatCode(() -> new JsonLimits(1, 1, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new JsonLimits(32, 1024, 50, 4096)).doesNotThrowAnyException();
        assertThatCode(() -> new JsonLimits(
                JsonLimits.DEFAULTS.maxNestingDepth(),
                JsonLimits.DEFAULTS.maxStringLength(),
                JsonLimits.DEFAULTS.maxNumberLength(),
                JsonLimits.DEFAULTS.maxTotalBytes())).doesNotThrowAnyException();
    }

    @Test
    void raisingMaxNestingDepthThrows() {
        assertThatThrownBy(() -> new JsonLimits(65, 100, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void raisingMaxStringLengthThrows() {
        assertThatThrownBy(() -> new JsonLimits(64, 262_145, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void raisingMaxNumberLengthThrows() {
        assertThatThrownBy(() -> new JsonLimits(64, 100, 101, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void raisingMaxTotalBytesThrows() {
        assertThatThrownBy(() -> new JsonLimits(64, 100, 100, 1_048_577))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroOrNegativeValuesThrow() {
        assertThatThrownBy(() -> new JsonLimits(0, 100, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonLimits(64, 0, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonLimits(64, 100, -1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JsonLimits(64, 100, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
