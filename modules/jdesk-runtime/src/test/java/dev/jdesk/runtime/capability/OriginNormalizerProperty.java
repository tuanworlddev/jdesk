package dev.jdesk.runtime.capability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of origin normalization: totality (only {@link JDeskException} with
 * INVALID_REQUEST for bad input), idempotence, and case-insensitivity of scheme/host.
 */
class OriginNormalizerProperty {

    @Property(tries = 2000)
    void arbitraryStringsEitherNormalizeOrFailWithInvalidRequest(@ForAll String raw) {
        try {
            String normalized = OriginNormalizer.normalize(raw);
            assertThat(normalized).isNotNull();
        } catch (JDeskException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        }
        // No other exception type may escape.
    }

    @Property(tries = 1000)
    void normalizationIsIdempotent(@ForAll("wellFormedOrigins") String origin) {
        String once = OriginNormalizer.normalize(origin);
        assertThat(OriginNormalizer.normalize(once)).isEqualTo(once);
    }

    @Property(tries = 1000)
    void caseOfSchemeAndHostNeverMatters(@ForAll("wellFormedOrigins") String origin) {
        String lower = OriginNormalizer.normalize(origin.toLowerCase(Locale.ROOT));
        String upper = OriginNormalizer.normalize(origin.toUpperCase(Locale.ROOT));
        assertThat(upper).isEqualTo(lower);
    }

    @Property(tries = 1000)
    void normalizedFormNeverContainsDefaultPortOrPath(@ForAll("wellFormedOrigins") String origin) {
        String normalized = OriginNormalizer.normalize(origin);
        int schemeEnd = normalized.indexOf("://");
        assertThat(schemeEnd).isGreaterThan(0);
        assertThat(normalized.substring(schemeEnd + 3)).doesNotContain("/");
        if (normalized.startsWith("http://")) {
            assertThat(normalized).doesNotEndWith(":80");
        }
        if (normalized.startsWith("https://")) {
            assertThat(normalized).doesNotEndWith(":443");
        }
    }

    @Provide
    Arbitrary<String> wellFormedOrigins() {
        Arbitrary<String> scheme = Arbitraries.of("http", "https", "HTTP", "HtTpS");
        Arbitrary<String> label = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(10);
        Arbitrary<String> host = Combinators.combine(label, label)
                .as((a, b) -> a + "." + b);
        Arbitrary<String> port = Arbitraries.of("", ":80", ":443", ":8080", ":5173", ":65535");
        return Combinators.combine(scheme, host, port)
                .as((s, h, p) -> s + "://" + h + p);
    }
}
