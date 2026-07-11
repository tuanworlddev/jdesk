package dev.jdesk.runtime.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Canonical origin form (spec sections 12.1/17.2: origin normalization). Raw string
 * equality is never a security decision; every comparison goes through this normalizer.
 */
class OriginNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "http://example.com, http://example.com",
            "HTTP://EXAMPLE.COM, http://example.com",
            "HtTpS://ExAmPlE.CoM, https://example.com",
            "http://example.com:80, http://example.com",
            "https://example.com:443, https://example.com",
            "http://example.com:8080, http://example.com:8080",
            "https://example.com:80, https://example.com:80",
            "http://example.com:443, http://example.com:443",
            "http://localhost:5173, http://localhost:5173",
            "jdesk://app, jdesk://app",
            "JDESK://APP, jdesk://app",
            "http://example.com/, http://example.com",
    })
    void normalizes(String raw, String expected) {
        assertThat(OriginNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void pathIsRejected() {
        assertThatThrownBy(() -> OriginNormalizer.normalize("http://example.com/x"))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void userInfoIsRejected() {
        assertThatThrownBy(() -> OriginNormalizer.normalize("http://user@example.com"))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> OriginNormalizer.normalize("http://user:pass@example.com"))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a uri",
            "%%%",
            "://nohost",
            "http://",
            "example.com",       // no scheme
            "/relative/path",
            "http:///path-only",
    })
    void garbageIsRejectedWithInvalidRequest(String raw) {
        assertThatThrownBy(() -> OriginNormalizer.normalize(raw))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void nullIsRejectedWithInvalidRequest() {
        assertThatThrownBy(() -> OriginNormalizer.normalize(null))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
