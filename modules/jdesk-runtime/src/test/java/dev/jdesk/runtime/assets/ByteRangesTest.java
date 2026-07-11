package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Single-range parsing semantics (RFC 9110 section 14). */
class ByteRangesTest {

    @Test
    void boundedRangeParses() {
        ByteRanges.Result result = ByteRanges.parse("bytes=0-499", 1000);
        assertThat(result.kind()).isEqualTo(ByteRanges.Kind.PARTIAL);
        assertThat(result.start()).isZero();
        assertThat(result.endInclusive()).isEqualTo(499);
        assertThat(result.length()).isEqualTo(500);
    }

    @Test
    void openEndedRangeRunsToEnd() {
        ByteRanges.Result result = ByteRanges.parse("bytes=500-", 1000);
        assertThat(result.kind()).isEqualTo(ByteRanges.Kind.PARTIAL);
        assertThat(result.start()).isEqualTo(500);
        assertThat(result.endInclusive()).isEqualTo(999);
    }

    @Test
    void suffixRangeTakesLastBytes() {
        ByteRanges.Result result = ByteRanges.parse("bytes=-200", 1000);
        assertThat(result.kind()).isEqualTo(ByteRanges.Kind.PARTIAL);
        assertThat(result.start()).isEqualTo(800);
        assertThat(result.endInclusive()).isEqualTo(999);
    }

    @Test
    void suffixLargerThanAssetServesWholeAsset() {
        ByteRanges.Result result = ByteRanges.parse("bytes=-5000", 1000);
        assertThat(result.kind()).isEqualTo(ByteRanges.Kind.PARTIAL);
        assertThat(result.start()).isZero();
        assertThat(result.endInclusive()).isEqualTo(999);
    }

    @Test
    void endIsClampedToSize() {
        ByteRanges.Result result = ByteRanges.parse("bytes=900-5000", 1000);
        assertThat(result.endInclusive()).isEqualTo(999);
    }

    @Test
    void startBeyondSizeIsUnsatisfiable() {
        assertThat(ByteRanges.parse("bytes=1000-1200", 1000).kind())
                .isEqualTo(ByteRanges.Kind.UNSATISFIABLE);
        assertThat(ByteRanges.parse("bytes=-0", 1000).kind())
                .isEqualTo(ByteRanges.Kind.UNSATISFIABLE);
        assertThat(ByteRanges.parse("bytes=-5", 0).kind())
                .isEqualTo(ByteRanges.Kind.UNSATISFIABLE);
    }

    @Test
    void malformedHeadersAreIgnored() {
        for (String bad : new String[] {
                "bytes=", "bytes=-", "bytes=abc-def", "bytes=5-2", "bytes=0-2,4-6",
                "chunks=0-5", "bytes 0-5", "", "bytes=--5", "bytes=1e3-"}) {
            assertThat(ByteRanges.parse(bad, 1000).kind())
                    .as("Range: %s", bad)
                    .isEqualTo(ByteRanges.Kind.IGNORE);
        }
        assertThat(ByteRanges.parse(null, 1000).kind()).isEqualTo(ByteRanges.Kind.IGNORE);
    }

    @Test
    void unknownTotalSizeIsIgnored() {
        assertThat(ByteRanges.parse("bytes=0-5", -1).kind()).isEqualTo(ByteRanges.Kind.IGNORE);
    }

    @Test
    void headerIsCaseInsensitiveAndTolerantOfWhitespace() {
        ByteRanges.Result result = ByteRanges.parse("BYTES= 0 - 499 ", 1000);
        assertThat(result.kind()).isEqualTo(ByteRanges.Kind.PARTIAL);
        assertThat(result.start()).isZero();
        assertThat(result.endInclusive()).isEqualTo(499);
    }
}
