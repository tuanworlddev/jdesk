package dev.jdesk.runtime.assets;

import java.util.Locale;

/**
 * Single-range {@code Range: bytes=...} parsing (RFC 9110 section 14). Multi-range and
 * malformed headers are IGNOREd — serving the full representation with 200 is always a
 * valid response to a Range request. Only a syntactically valid range that lies entirely
 * beyond the representation is UNSATISFIABLE (416).
 */
final class ByteRanges {

    enum Kind { IGNORE, PARTIAL, UNSATISFIABLE }

    /** {@code start}/{@code endInclusive} are meaningful only when {@code kind} is PARTIAL. */
    record Result(Kind kind, long start, long endInclusive) {
        static final Result IGNORE = new Result(Kind.IGNORE, -1, -1);
        static final Result UNSATISFIABLE = new Result(Kind.UNSATISFIABLE, -1, -1);

        long length() {
            return endInclusive - start + 1;
        }
    }

    private ByteRanges() {
    }

    static Result parse(String headerValue, long totalSize) {
        if (headerValue == null || totalSize < 0) {
            return Result.IGNORE;
        }
        String value = headerValue.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes=")) {
            return Result.IGNORE;
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            return Result.IGNORE; // multi-range not supported; full response is valid
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Result.IGNORE;
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        try {
            if (first.isEmpty()) {
                // suffix form: bytes=-N (last N bytes)
                if (last.isEmpty()) {
                    return Result.IGNORE;
                }
                long suffix = Long.parseLong(last);
                if (suffix < 0) {
                    return Result.IGNORE; // malformed, e.g. "bytes=--5"
                }
                if (suffix == 0 || totalSize == 0) {
                    return Result.UNSATISFIABLE;
                }
                long start = Math.max(0, totalSize - suffix);
                return new Result(Kind.PARTIAL, start, totalSize - 1);
            }
            long start = Long.parseLong(first);
            if (start < 0) {
                return Result.IGNORE;
            }
            if (start >= totalSize) {
                return Result.UNSATISFIABLE;
            }
            long end = last.isEmpty() ? totalSize - 1 : Long.parseLong(last);
            if (end < start) {
                return Result.IGNORE;
            }
            return new Result(Kind.PARTIAL, start, Math.min(end, totalSize - 1));
        } catch (NumberFormatException e) {
            return Result.IGNORE;
        }
    }
}
