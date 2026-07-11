package dev.jdesk.runtime.json;

/**
 * Defensive JSON bounds (spec section 11). Values may be lowered but never raised above
 * these defaults.
 */
public record JsonLimits(int maxNestingDepth, int maxStringLength, int maxNumberLength, int maxTotalBytes) {
    // Literal ceilings: the compact constructor must not read DEFAULTS, which is still
    // null while DEFAULTS itself is being constructed during class initialization.
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_STRING_LENGTH = 262_144;
    private static final int MAX_NUMBER_LENGTH = 100;
    private static final int MAX_TOTAL_BYTES = 1_048_576;

    public static final JsonLimits DEFAULTS = new JsonLimits(
            MAX_NESTING_DEPTH, MAX_STRING_LENGTH, MAX_NUMBER_LENGTH, MAX_TOTAL_BYTES);

    public JsonLimits {
        if (maxNestingDepth < 1 || maxNestingDepth > MAX_NESTING_DEPTH
                || maxStringLength < 1 || maxStringLength > MAX_STRING_LENGTH
                || maxNumberLength < 1 || maxNumberLength > MAX_NUMBER_LENGTH
                || maxTotalBytes < 1 || maxTotalBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException(
                    "JSON limits may only be lowered from the defaults ("
                            + MAX_NESTING_DEPTH + ", " + MAX_STRING_LENGTH + ", "
                            + MAX_NUMBER_LENGTH + ", " + MAX_TOTAL_BYTES + ")");
        }
    }
}
