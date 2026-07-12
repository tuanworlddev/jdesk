package dev.jdesk.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.SystemTheme;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Windows desktop-integration primitives (GAP-004) via Win32/Shell. Self-contained bindings
 * (advapi32/user32/kernel32/shell32).
 *
 * <p>Honest status: compile-verified only — no Windows environment on the authoring machine.
 * Runtime verification belongs to the Windows native CI lane. Windows differs from macOS in
 * model (no global menu bar; the app icon is normally the packaged .exe resource via
 * {@code jpackage --icon}); those differences are noted at each call site.
 */
final class WindowsDesktop {
    private static final Logger LOG = System.getLogger(WindowsDesktop.class.getName());

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena ARENA = Arena.ofAuto();
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", ARENA);
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", ARENA);
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", ARENA);

    private static final MemorySegment HKEY_CURRENT_USER = MemorySegment.ofAddress(0x80000001L);
    private static final int RRF_RT_REG_DWORD = 0x00000018;
    private static final int GMEM_MOVEABLE = 0x0002;

    private static final MethodHandle REG_GET_VALUE = down(ADVAPI32, "RegGetValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS,
                    ADDRESS));
    private static final MethodHandle REGISTER_CLIPBOARD_FORMAT = down(USER32,
            "RegisterClipboardFormatW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle OPEN_CLIPBOARD = down(USER32, "OpenClipboard",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE_CLIPBOARD = down(USER32, "CloseClipboard",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle EMPTY_CLIPBOARD = down(USER32, "EmptyClipboard",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_CLIPBOARD_DATA = down(USER32, "GetClipboardData",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle SET_CLIPBOARD_DATA = down(USER32, "SetClipboardData",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_ALLOC = down(KERNEL32, "GlobalAlloc",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GLOBAL_LOCK = down(KERNEL32, "GlobalLock",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GLOBAL_UNLOCK = down(KERNEL32, "GlobalUnlock",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_SIZE = down(KERNEL32, "GlobalSize",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    private WindowsDesktop() {
    }

    /** Reads {@code AppsUseLightTheme} (0 = dark, 1 = light) from the current user registry. */
    static SystemTheme systemTheme() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment subKey = wide(arena,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize");
            MemorySegment value = wide(arena, "AppsUseLightTheme");
            MemorySegment data = arena.allocate(JAVA_INT);
            MemorySegment size = arena.allocate(JAVA_INT);
            size.set(JAVA_INT, 0, 4);
            int rc = (int) REG_GET_VALUE.invokeExact(HKEY_CURRENT_USER, subKey, value,
                    RRF_RT_REG_DWORD, MemorySegment.NULL, data, size);
            if (rc == 0) {
                return data.get(JAVA_INT, 0) == 0 ? SystemTheme.DARK : SystemTheme.LIGHT;
            }
            return SystemTheme.LIGHT; // key absent -> light (Windows default)
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Could not read Windows theme", null, t);
        }
    }

    /** Reads binary clipboard data for a registered format named {@code type}; null if absent. */
    static byte[] readClipboard(String type) {
        int format = registerFormat(type);
        try {
            if ((int) OPEN_CLIPBOARD.invokeExact(MemorySegment.NULL) == 0) {
                return null;
            }
            try {
                MemorySegment handle = (MemorySegment) GET_CLIPBOARD_DATA.invokeExact(format);
                if (handle.equals(MemorySegment.NULL)) {
                    return null;
                }
                long size = (long) GLOBAL_SIZE.invokeExact(handle);
                MemorySegment locked = (MemorySegment) GLOBAL_LOCK.invokeExact(handle);
                if (locked.equals(MemorySegment.NULL) || size <= 0) {
                    return new byte[0];
                }
                try {
                    return locked.reinterpret(size).toArray(JAVA_BYTE);
                } finally {
                    int unused = (int) GLOBAL_UNLOCK.invokeExact(handle);
                }
            } finally {
                int unused = (int) CLOSE_CLIPBOARD.invokeExact();
            }
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Clipboard read failed", null, t);
        }
    }

    /** Writes binary clipboard data under a registered format named {@code type}. */
    static void writeClipboard(String type, byte[] data) {
        int format = registerFormat(type);
        try {
            if ((int) OPEN_CLIPBOARD.invokeExact(MemorySegment.NULL) == 0) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Could not open clipboard");
            }
            try {
                int unusedEmpty = (int) EMPTY_CLIPBOARD.invokeExact();
                MemorySegment global = (MemorySegment) GLOBAL_ALLOC.invokeExact(GMEM_MOVEABLE,
                        (long) Math.max(1, data.length));
                if (global.equals(MemorySegment.NULL)) {
                    throw new JDeskException(ErrorCode.ILLEGAL_STATE, "GlobalAlloc failed");
                }
                MemorySegment locked = (MemorySegment) GLOBAL_LOCK.invokeExact(global);
                if (data.length > 0) {
                    MemorySegment.copy(data, 0, locked.reinterpret(data.length), JAVA_BYTE, 0,
                            data.length);
                }
                int unusedUnlock = (int) GLOBAL_UNLOCK.invokeExact(global);
                MemorySegment set = (MemorySegment) SET_CLIPBOARD_DATA.invokeExact(format, global);
                if (set.equals(MemorySegment.NULL)) {
                    throw new JDeskException(ErrorCode.ILLEGAL_STATE, "SetClipboardData failed");
                }
            } finally {
                int unused = (int) CLOSE_CLIPBOARD.invokeExact();
            }
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Clipboard write failed", null, t);
        }
    }

    private static int registerFormat(String type) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) REGISTER_CLIPBOARD_FORMAT.invokeExact(wide(arena, type));
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "RegisterClipboardFormat failed",
                    null, t);
        }
    }

    static MemorySegment wide(Arena arena, String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment segment = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, segment, JAVA_BYTE, 0, utf16.length);
        return segment;
    }

    static MethodHandle down(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(name), descriptor);
    }
}
