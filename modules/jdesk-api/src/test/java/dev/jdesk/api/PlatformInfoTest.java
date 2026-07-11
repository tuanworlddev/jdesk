package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** PlatformInfo record accessors and null validation. */
class PlatformInfoTest {

    @Test
    void accessorsReturnComponents() {
        PlatformInfo info = new PlatformInfo("macOS", "15.0", "aarch64");
        assertThat(info.osName()).isEqualTo("macOS");
        assertThat(info.osVersion()).isEqualTo("15.0");
        assertThat(info.architecture()).isEqualTo("aarch64");
    }

    @Test
    void nullComponentsRejected() {
        assertThatThrownBy(() -> new PlatformInfo(null, "1", "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlatformInfo("os", null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlatformInfo("os", "1", null))
                .isInstanceOf(NullPointerException.class);
    }
}
