package dev.jdesk.runtime.assets;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strict production asset path normalization (spec section 9.1). Rejects — never
 * repairs — traversal, encoded traversal, NUL, backslash, absolute/drive forms, invalid
 * percent encoding, control characters, empty segments, and over-long paths. The result
 * is a canonical relative path ("" means the root document).
 */
public final class AssetPaths {
    private static final int MAX_PATH_LENGTH = 2048;
    private static final int MAX_SEGMENTS = 64;

    private AssetPaths() {
    }

    /**
     * @param rawPath the raw (still percent-encoded) URI path, e.g. {@code /a/b%20c.js}
     * @return canonical relative path, or empty when the request must be rejected
     */
    public static Optional<String> normalize(String rawPath) {
        if (rawPath == null || rawPath.length() > MAX_PATH_LENGTH) {
            return Optional.empty();
        }
        if (rawPath.isEmpty() || rawPath.equals("/")) {
            return Optional.of("");
        }
        if (!rawPath.startsWith("/")) {
            return Optional.empty();
        }
        String decoded = percentDecodeUtf8(rawPath.substring(1));
        if (decoded == null) {
            return Optional.empty();
        }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f || c == ':') {
                return Optional.empty();
            }
        }
        List<String> segments = new ArrayList<>();
        for (String segment : decoded.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return Optional.empty();
            }
            // Reject trailing-dot/space forms that are ambiguous on some filesystems.
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return Optional.empty();
            }
            segments.add(segment);
        }
        if (segments.size() > MAX_SEGMENTS) {
            return Optional.empty();
        }
        return Optional.of(String.join("/", segments));
    }

    /** Strict decoder: invalid %XX, truncated escapes, or invalid UTF-8 return null. */
    private static String percentDecodeUtf8(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%') {
                if (i + 2 >= input.length()) {
                    return null;
                }
                int hi = Character.digit(input.charAt(i + 1), 16);
                int lo = Character.digit(input.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else if (c > 0x7f) {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
