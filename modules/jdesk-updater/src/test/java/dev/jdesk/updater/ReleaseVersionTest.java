package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReleaseVersionTest {
    @Test void followsSemanticVersionPrecedence() {
        assertThat(ReleaseVersion.parse("1.0.0"))
                .isGreaterThan(ReleaseVersion.parse("1.0.0-rc.1"))
                .isLessThan(ReleaseVersion.parse("1.0.1"));
        assertThat(ReleaseVersion.parse("1.0.0-beta.11"))
                .isGreaterThan(ReleaseVersion.parse("1.0.0-beta.2"));
        assertThat(ReleaseVersion.parse("1.0.0+build.2"))
                .isEqualByComparingTo(ReleaseVersion.parse("1.0.0+build.9"));
    }

    @Test void rejectsNonSemanticAndAmbiguousVersions() {
        assertThatThrownBy(() -> ReleaseVersion.parse("v1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReleaseVersion.parse("1.0.0-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
