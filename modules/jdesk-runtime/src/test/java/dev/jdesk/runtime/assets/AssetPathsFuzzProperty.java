package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based fuzzing of {@link AssetPaths#normalize} (spec section 17.2: use
 * property-based tests for path parsing). The normalizer must be total (never throw)
 * and every accepted path must uphold the containment invariants that the asset
 * sources rely on.
 */
class AssetPathsFuzzProperty {

    @Property(tries = 2000)
    void neverThrowsOnArbitraryInput(@ForAll String raw) {
        // Totality: any input produces Optional.empty() or a value, never an exception.
        AssetPaths.normalize(raw);
        AssetPaths.normalize("/" + raw); // exercise the decode path more often
    }

    @Property(tries = 2000)
    void acceptedPathsUpholdContainmentInvariants(@ForAll String raw) {
        checkInvariants(AssetPaths.normalize(raw));
        checkInvariants(AssetPaths.normalize("/" + raw));
    }

    private static void checkInvariants(Optional<String> result) {
        if (result.isEmpty()) {
            return;
        }
        String path = result.get();
        assertThat(path).doesNotContain("\\");
        assertThat(path).doesNotContain(":");
        assertThat(path).doesNotStartWith("/");
        path.chars().forEach(c -> {
            assertThat(c).as("control character in %s", path).isGreaterThanOrEqualTo(0x20);
            assertThat(c).as("DEL in %s", path).isNotEqualTo(0x7f);
        });
        if (!path.isEmpty()) {
            for (String segment : path.split("/", -1)) {
                assertThat(segment).as("empty segment in %s", path).isNotEmpty();
                assertThat(segment).as("dot segment in %s", path).isNotEqualTo(".");
                assertThat(segment).as("traversal segment in %s", path).isNotEqualTo("..");
                assertThat(segment).as("trailing dot in %s", path).doesNotEndWith(".");
                assertThat(segment).as("trailing space in %s", path).doesNotEndWith(" ");
            }
        }
    }

    @Property(tries = 500)
    void simpleAlphanumericPathsRoundTripUnchanged(@ForAll("simpleSegments") List<String> segments) {
        String joined = String.join("/", segments);
        assertThat(AssetPaths.normalize("/" + joined)).contains(joined);
    }

    @Provide
    Arbitrary<List<String>> simpleSegments() {
        Arbitrary<String> segment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(12);
        return segment.list().ofMinSize(1).ofMaxSize(8);
    }
}
