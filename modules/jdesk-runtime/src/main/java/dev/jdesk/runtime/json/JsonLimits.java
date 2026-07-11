package dev.jdesk.runtime.json;

/**
 * Defensive JSON bounds (spec section 11). Values may be lowered but never raised above
 * these defaults.
 */
public record JsonLimits(int maxNestingDepth, int maxStringLength, int maxNumberLength, int maxTotalBytes) {
    public static final JsonLimits DEFAULTS = new JsonLimits(64, 262_144, 100, 1_048_576);

    public JsonLimits {
        if (maxNestingDepth < 1 || maxNestingDepth > DEFAULTS.maxNestingDepth()
                || maxStringLength < 1 || maxStringLength > DEFAULTS.maxStringLength()
                || maxNumberLength < 1 || maxNumberLength > DEFAULTS.maxNumberLength()
                || maxTotalBytes < 1 || maxTotalBytes > DEFAULTS.maxTotalBytes()) {
            throw new IllegalArgumentException(
                    "JSON limits may only be lowered from the defaults " + DEFAULTS);
        }
    }
}
