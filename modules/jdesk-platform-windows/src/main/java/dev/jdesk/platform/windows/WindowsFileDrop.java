package dev.jdesk.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OS file drops onto a Win32 window: {@code DragAcceptFiles} opts the HWND into WM_DROPFILES, and
 * {@link #extractPaths(long)} pulls the dropped absolute paths out of the HDROP via
 * {@code DragQueryFileW}. The WM_DROPFILES message is routed from {@link WindowsWindow#wndProc}.
 *
 * <p>Honest status: compile-verified only — no Windows environment on the authoring machine. The
 * shell drag messages are runtime-verified on the Windows native CI lane.
 */
final class WindowsFileDrop {
    /** {@code iFile == 0xFFFFFFFF} asks DragQueryFileW for the dropped-file count. */
    private static final int DRAG_QUERY_COUNT = 0xFFFFFFFF;

    private static final SymbolLookup SHELL32 =
            SymbolLookup.libraryLookup("shell32.dll", Arena.ofAuto());
    private static final MethodHandle DRAG_ACCEPT_FILES = WindowsDesktop.down(SHELL32,
            "DragAcceptFiles", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle DRAG_QUERY_FILE = WindowsDesktop.down(SHELL32,
            "DragQueryFileW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle DRAG_FINISH = WindowsDesktop.down(SHELL32, "DragFinish",
            FunctionDescriptor.ofVoid(ADDRESS));

    private WindowsFileDrop() {
    }

    /** Opts {@code hwnd} in (or out) of receiving WM_DROPFILES. UI thread only. */
    static void setAccept(MemorySegment hwnd, boolean accept) {
        try {
            DRAG_ACCEPT_FILES.invokeExact(hwnd, accept ? 1 : 0);
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "DragAcceptFiles failed", null, t);
        }
    }

    /** Extracts the dropped paths from an HDROP (WM_DROPFILES wParam) and frees it. */
    static List<Path> extractPaths(long hdropAddress) {
        MemorySegment hdrop = MemorySegment.ofAddress(hdropAddress);
        List<Path> paths = new ArrayList<>();
        try {
            int count = (int) DRAG_QUERY_FILE.invokeExact(hdrop, DRAG_QUERY_COUNT,
                    MemorySegment.NULL, 0);
            try (Arena arena = Arena.ofConfined()) {
                for (int i = 0; i < count; i++) {
                    // First call (NULL buffer) returns the length in chars, excluding the NUL.
                    int len = (int) DRAG_QUERY_FILE.invokeExact(hdrop, i, MemorySegment.NULL, 0);
                    MemorySegment buf = arena.allocate((len + 1L) * 2); // WCHAR + NUL
                    int unused = (int) DRAG_QUERY_FILE.invokeExact(hdrop, i, buf, len + 1);
                    String path = WideStrings.read(buf);
                    if (!path.isEmpty()) {
                        try {
                            paths.add(Path.of(path));
                        } catch (RuntimeException ignored) {
                            // skip a path this JVM cannot represent
                        }
                    }
                }
            }
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "DragQueryFile failed", null, t);
        } finally {
            try {
                DRAG_FINISH.invokeExact(hdrop);
            } catch (Throwable ignored) {
                // best effort; the HDROP is freed by the shell on window teardown regardless
            }
        }
        return paths;
    }
}
