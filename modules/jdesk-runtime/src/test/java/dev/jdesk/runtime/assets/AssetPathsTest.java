package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asset path traversal corpus (spec section 17.2, behavior per spec section 9.1).
 * Each case pairs a raw (still percent-encoded) request path with either the expected
 * canonical relative path or {@code null} meaning the request must be rejected
 * (the resolver turns a rejection into a deterministic 404).
 */
class AssetPathsTest {

    /** null expected == must be rejected. */
    static Stream<Arguments> corpus() {
        return Stream.of(
                // --- plain traversal ---
                rejected(".."),                     // no leading slash and traversal
                rejected("../"),
                rejected("/.."),
                rejected("/../"),
                rejected("/../etc/passwd"),
                rejected("/a/../b"),
                rejected("/a/.."),
                rejected("/a/b/../../c"),

                // --- percent-encoded traversal (single decode) ---
                rejected("%2e%2e"),                 // also missing leading slash
                rejected("/%2e%2e"),
                rejected("%2e%2e%2f"),
                rejected("/%2e%2e%2f"),
                rejected("/%2e%2e/x"),
                rejected("%2E%2E"),                 // uppercase hex digits
                rejected("/%2E%2E"),
                rejected("/%2E%2E/x"),
                rejected("..%2f"),
                rejected("/..%2f"),
                rejected("/..%2fsecret"),
                rejected("%2f.."),
                rejected("/%2f.."),
                rejected("/a%2f..%2fb"),            // encoded slashes still form ".." segment
                rejected("/.%2e/x"),                // mixed literal/encoded ".."

                // --- double encoding: decoded exactly once, "%2e%2e" is a literal name ---
                accepted("/%252e%252e", "%2e%2e"),
                accepted("/%252e%252e%252fx", "%2e%2e%2fx"),

                // --- invalid / overlong UTF-8 percent sequences ---
                rejected("/%c0%ae"),                // overlong '.'
                rejected("/%c0%ae%c0%ae/x"),
                rejected("/%e0%80%af"),             // overlong '/'
                rejected("/%c0%af"),
                rejected("/a%80b"),                 // bare continuation byte

                // --- NUL and control characters ---
                rejected("/%00"),
                rejected("/a%00b"),
                rejected("/a%00.js"),
                rejected("/%01"),
                rejected("/a%1fb"),
                rejected("/a%7fb"),                 // DEL
                rejected("/a\tb"),                  // literal control char

                // --- backslash ambiguity ---
                rejected("\\"),
                rejected("/\\"),
                rejected("a\\b"),
                rejected("/a\\b"),
                rejected("/%5c"),
                rejected("/a%5cb"),
                rejected("/..%5c..%5cwindows"),

                // --- absolute / drive-letter forms (':' rejected) ---
                rejected("C:"),
                rejected("C:/x"),
                rejected("C:\\x"),
                rejected("/c:/x"),
                rejected("/C:/windows/system32"),
                rejected("/a:b"),
                rejected("/%3a"),                   // encoded ':'

                // --- empty and dot segments ---
                rejected("//"),
                rejected("/a//b"),
                rejected("/a/"),                    // trailing empty segment
                rejected("."),
                rejected("./"),
                rejected("/."),
                rejected("/./"),
                rejected("/a/."),
                rejected("/a/./b"),

                // --- trailing dot / trailing space segments (Windows ambiguity) ---
                rejected("/a."),
                rejected("a./"),                    // no leading slash anyway
                rejected("/a./b"),
                rejected("/..."),                   // ends with '.'
                rejected("/a%20"),                  // decoded trailing space
                rejected("/a /b"),                  // literal trailing space segment
                rejected("/a%20/b"),

                // --- invalid percent encoding ---
                rejected("/%zz"),
                rejected("/%2"),                    // truncated escape
                rejected("/%"),
                rejected("/a%"),
                rejected("/a%2"),
                rejected("/%g1"),
                rejected("/%-1"),

                // --- missing leading slash ---
                rejected("index.html"),
                rejected("a/b"),

                // --- valid paths ---
                accepted("/", ""),
                accepted("", ""),                   // engines may report an empty path for the root
                accepted("/index.html", "index.html"),
                accepted("/a/b/c.js", "a/b/c.js"),
                accepted("/app.3f9d2c1a.js", "app.3f9d2c1a.js"),
                accepted("/a..b", "a..b"),          // dots inside a segment are not traversal
                accepted("/..a", "..a"),
                accepted("/a.b.c", "a.b.c"),
                accepted("/a%20b.js", "a b.js"),    // encoded space inside a segment is fine
                accepted("/a b.js", "a b.js"),      // literal space (>= 0x20) is not rejected
                accepted("/a%2fb", "a/b"),          // encoded slash becomes a real separator
                accepted("/_-~()!.js", "_-~()!.js"),

                // --- unicode ---
                accepted("/caf%C3%A9.js", "café.js"),
                accepted("/café.js", "café.js"),    // literal non-ASCII round-trips through UTF-8
                accepted("/%E2%82%AC.txt", "€.txt")
        );
    }

    static Arguments rejected(String raw) {
        return Arguments.of(raw, null);
    }

    static Arguments accepted(String raw, String normalized) {
        return Arguments.of(raw, normalized);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    void corpusCase(String raw, String expected) {
        Optional<String> result = AssetPaths.normalize(raw);
        if (expected == null) {
            assertThat(result).as("path %s must be rejected", raw).isEmpty();
        } else {
            assertThat(result).as("path %s must normalize", raw).contains(expected);
        }
    }

    @Test
    void nullIsRejected() {
        assertThat(AssetPaths.normalize(null)).isEmpty();
    }

    @Test
    void pathsLongerThan2048AreRejected() {
        String tooLong = "/" + "a".repeat(2100);
        assertThat(AssetPaths.normalize(tooLong)).isEmpty();
    }

    @Test
    void pathAtExactly2048CharsIsAccepted() {
        String raw = "/" + "a".repeat(2047); // total length 2048
        assertThat(AssetPaths.normalize(raw)).contains("a".repeat(2047));
    }

    @Test
    void moreThan64SegmentsIsRejected() {
        String raw = "/" + "a/".repeat(64) + "a"; // 65 segments
        assertThat(AssetPaths.normalize(raw)).isEmpty();
    }

    @Test
    void exactly64SegmentsIsAccepted() {
        String raw = "/" + "a/".repeat(63) + "a"; // 64 segments
        assertThat(AssetPaths.normalize(raw)).isPresent();
    }
}
