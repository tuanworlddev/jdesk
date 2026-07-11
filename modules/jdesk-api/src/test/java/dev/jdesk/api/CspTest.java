package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CspTest {

    @Test
    void defaultsMatchTheStrictPolicy() {
        String csp = Csp.defaults().build();
        assertThat(csp)
                .contains("default-src 'self'")
                .contains("connect-src 'self'")
                .contains("object-src 'none'")
                .contains("base-uri 'none'")
                .contains("frame-ancestors 'none'");
    }

    @Test
    void overridingOneDirectiveKeepsTheRest() {
        String csp = Csp.defaults()
                .connectSrc("'self'", "ws://127.0.0.1:7777")
                .imgSrc("'self'", "data:", "https://cdn.example.com")
                .build();
        assertThat(csp)
                .contains("connect-src 'self' ws://127.0.0.1:7777")
                .contains("img-src 'self' data: https://cdn.example.com")
                // untouched directives remain exactly as the default set them
                .contains("object-src 'none'")
                .contains("script-src 'self'");
    }

    @Test
    void directiveWithNoSourcesRemovesIt() {
        String csp = Csp.defaults().directive("frame-ancestors").build();
        assertThat(csp).doesNotContain("frame-ancestors");
    }

    @Test
    void arbitraryDirectiveByName() {
        assertThat(Csp.empty().directive("media-src", "'self'", "blob:").build())
                .isEqualTo("media-src 'self' blob:");
    }

    @Test
    void emptyPolicyIsRejected() {
        assertThatThrownBy(() -> Csp.empty().build())
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void blankDirectiveNameIsRejected() {
        assertThatThrownBy(() -> Csp.defaults().directive("  ", "'self'"))
                .isInstanceOf(JDeskException.class);
    }
}
