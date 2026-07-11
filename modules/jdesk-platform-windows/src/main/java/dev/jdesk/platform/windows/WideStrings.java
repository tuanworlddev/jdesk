package dev.jdesk.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/** UTF-16LE (Windows PCWSTR/LPWSTR) conversion helpers. */
final class WideStrings {
    private WideStrings() {
    }

    /** Allocates a NUL-terminated UTF-16LE copy of {@code value}. */
    static MemorySegment alloc(Arena arena, String value) {
        return arena.allocateFrom(value, StandardCharsets.UTF_16LE);
    }

    /** Reads a NUL-terminated UTF-16LE string. */
    static String read(MemorySegment segment) {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            return "";
        }
        return segment.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** Reads a NUL-terminated UTF-16LE string and frees it with CoTaskMemFree. */
    static String readAndFreeCoTaskMem(MemorySegment segment) {
        try {
            return read(segment);
        } finally {
            if (segment != null && !segment.equals(MemorySegment.NULL)) {
                Win32.coTaskMemFree(segment);
            }
        }
    }
}
