package dev.jdesk.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.UUID;

/**
 * GUID struct helpers.
 *
 * <pre>
 * typedef struct _GUID {           // guiddef.h
 *   unsigned long  Data1;          // little-endian in memory
 *   unsigned short Data2;
 *   unsigned short Data3;
 *   unsigned char  Data4[8];       // as written in the canonical string
 * } GUID;                          // 16 bytes
 * </pre>
 */
final class Guids {
    private Guids() {
    }

    /** Allocates a GUID struct from its canonical string form. */
    static MemorySegment alloc(Arena arena, String canonical) {
        UUID uuid = UUID.fromString(canonical);
        MemorySegment guid = arena.allocate(16);
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        guid.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) (msb >>> 32));
        guid.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) (msb >>> 16));
        guid.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) msb);
        for (int i = 0; i < 8; i++) {
            guid.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) (lsb >>> (8 * (7 - i))));
        }
        return guid;
    }

    /** Compares a native GUID (riid) against a canonical string form. */
    static boolean equals(MemorySegment riid, String canonical) {
        UUID uuid = UUID.fromString(canonical);
        MemorySegment g = riid.reinterpret(16);
        long msb = ((long) g.get(ValueLayout.JAVA_INT_UNALIGNED, 0) << 32)
                | ((long) (g.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4) & 0xFFFF) << 16)
                | (g.get(ValueLayout.JAVA_SHORT_UNALIGNED, 6) & 0xFFFFL);
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            lsb = (lsb << 8) | (g.get(ValueLayout.JAVA_BYTE, 8 + i) & 0xFFL);
        }
        return msb == uuid.getMostSignificantBits() && lsb == uuid.getLeastSignificantBits();
    }
}
