package dev.jdesk.platform.windows;

import dev.jdesk.api.FileDialog;
import dev.jdesk.api.FileDialogResult;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * File open/save dialogs via comdlg32 {@code GetOpenFileNameW}/{@code GetSaveFileNameW}
 * and the {@code OPENFILENAMEW} struct. Compile-verified; the modal dialog cannot be
 * driven on headless CI, so behavioral verification is manual on Windows hardware.
 */
final class WindowsFileDialog {

    // OPENFILENAMEW field byte offsets (x64, natural alignment):
    // lStructSize 0, [pad] , hwndOwner 8, hInstance 16, lpstrFilter 24,
    // lpstrCustomFilter 32, nMaxCustFilter 40, nFilterIndex 44, lpstrFile 48,
    // nMaxFile 56, [pad], lpstrFileTitle 64, nMaxFileTitle 72, [pad],
    // lpstrInitialDir 80, lpstrTitle 88, Flags 96, ... total 152.
    private static final long SIZE = 152;
    private static final long OFF_STRUCT_SIZE = 0;
    private static final long OFF_FILTER = 24;         // lpstrFilter
    private static final long OFF_FILTER_INDEX = 44;   // nFilterIndex
    private static final long OFF_FILE = 48;           // lpstrFile
    private static final long OFF_MAX_FILE = 56;       // nMaxFile
    private static final long OFF_INITIAL_DIR = 80;    // lpstrInitialDir
    private static final long OFF_TITLE = 88;          // lpstrTitle
    private static final long OFF_FLAGS = 96;          // Flags
    private static final long OFF_DEF_EXT = 104;       // lpstrDefExt
    private static final int MAX_PATH_CHARS = 32768;

    private static final int OFN_ALLOWMULTISELECT = 0x00000200;
    private static final int OFN_EXPLORER = 0x00080000;
    private static final int OFN_PATHMUSTEXIST = 0x00000800;
    private static final int OFN_FILEMUSTEXIST = 0x00001000;
    private static final int OFN_OVERWRITEPROMPT = 0x00000002;

    private WindowsFileDialog() {
    }

    static FileDialogResult open(FileDialog.OpenDialog dialog) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fileBuffer = arena.allocate(JAVA_CHAR, MAX_PATH_CHARS);
            MemorySegment ofn = buildOfn(arena, fileBuffer, dialog.title(),
                    dialog.directory().orElse(null), dialog.filters());
            int flags = OFN_EXPLORER | OFN_PATHMUSTEXIST | OFN_FILEMUSTEXIST;
            if (dialog.allowMultiple()) {
                flags |= OFN_ALLOWMULTISELECT;
            }
            ofn.set(JAVA_INT, OFF_FLAGS, flags);
            int ok = (int) Win32.getOpenFileNameHandle().invokeExact(ofn);
            if (ok == 0) {
                return FileDialogResult.cancelled();
            }
            return new FileDialogResult(readSelection(fileBuffer, dialog.allowMultiple()));
        } catch (Throwable t) {
            throw new IllegalStateException("GetOpenFileNameW failed", t);
        }
    }

    static FileDialogResult save(FileDialog.SaveDialog dialog) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fileBuffer = arena.allocate(JAVA_CHAR, MAX_PATH_CHARS);
            dialog.suggestedName().ifPresent(name -> writeWide(fileBuffer, name));
            MemorySegment ofn = buildOfn(arena, fileBuffer, dialog.title(),
                    dialog.directory().orElse(null), dialog.filters());
            ofn.set(JAVA_INT, OFF_FLAGS, OFN_EXPLORER | OFN_OVERWRITEPROMPT);
            // Default extension appended when the user types a name without one, so a
            // save of "report" becomes "report.pdf" (matches macOS setAllowedFileTypes).
            dialog.filters().stream()
                    .flatMap(f -> f.extensions().stream())
                    .filter(e -> !e.isBlank())
                    .findFirst()
                    .ifPresent(ext -> ofn.set(ADDRESS, OFF_DEF_EXT, wide(arena, ext)));
            int ok = (int) Win32.getSaveFileNameHandle().invokeExact(ofn);
            if (ok == 0) {
                return FileDialogResult.cancelled();
            }
            List<String> paths = readSelection(fileBuffer, false);
            return paths.isEmpty() ? FileDialogResult.cancelled() : new FileDialogResult(paths);
        } catch (Throwable t) {
            throw new IllegalStateException("GetSaveFileNameW failed", t);
        }
    }

    private static MemorySegment buildOfn(Arena arena, MemorySegment fileBuffer, String title,
            String initialDir, List<FileDialog.Filter> filters) {
        MemorySegment ofn = arena.allocate(SIZE);
        ofn.set(JAVA_INT, OFF_STRUCT_SIZE, (int) SIZE);
        ofn.set(ADDRESS, OFF_FILE, fileBuffer);
        ofn.set(JAVA_INT, OFF_MAX_FILE, MAX_PATH_CHARS);
        ofn.set(JAVA_INT, OFF_FILTER_INDEX, 1);
        if (title != null && !title.isEmpty()) {
            ofn.set(ADDRESS, OFF_TITLE, wide(arena, title));
        }
        if (initialDir != null && !initialDir.isEmpty()) {
            ofn.set(ADDRESS, OFF_INITIAL_DIR, wide(arena, initialDir));
        }
        MemorySegment filter = buildFilter(arena, filters);
        if (filter != null) {
            ofn.set(ADDRESS, OFF_FILTER, filter);
        }
        return ofn;
    }

    /** comdlg32 filter: pairs of NUL-terminated "label" + "*.ext;*.ext", double-NUL end. */
    static MemorySegment buildFilter(Arena arena, List<FileDialog.Filter> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        StringBuilder spec = new StringBuilder();
        for (FileDialog.Filter f : filters) {
            String patterns = f.extensions().isEmpty()
                    ? "*.*"
                    : String.join(";", f.extensions().stream().map(e -> "*." + e).toList());
            spec.append(f.label()).append('\0').append(patterns).append('\0');
        }
        spec.append('\0');
        char[] chars = spec.toString().toCharArray();
        MemorySegment segment = arena.allocate(JAVA_CHAR, chars.length);
        for (int i = 0; i < chars.length; i++) {
            segment.setAtIndex(JAVA_CHAR, i, chars[i]);
        }
        return segment;
    }

    private static void writeWide(MemorySegment buffer, String value) {
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length && i < MAX_PATH_CHARS - 1; i++) {
            buffer.setAtIndex(JAVA_CHAR, i, chars[i]);
        }
        buffer.setAtIndex(JAVA_CHAR, Math.min(chars.length, MAX_PATH_CHARS - 1), '\0');
    }

    private static MemorySegment wide(Arena arena, String value) {
        char[] chars = value.toCharArray();
        MemorySegment segment = arena.allocate(JAVA_CHAR, chars.length + 1);
        for (int i = 0; i < chars.length; i++) {
            segment.setAtIndex(JAVA_CHAR, i, chars[i]);
        }
        segment.setAtIndex(JAVA_CHAR, chars.length, '\0');
        return segment;
    }

    /**
     * Reads the result buffer. Single-select: one NUL-terminated path. Multi-select
     * (Explorer style): directory, NUL, then file names each NUL-terminated, double-NUL
     * end; a lone path (no second segment) means one file was chosen.
     */
    static List<String> readSelection(MemorySegment buffer, boolean multi) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (long i = 0; i < MAX_PATH_CHARS; i++) {
            char c = buffer.getAtIndex(JAVA_CHAR, i);
            if (c == '\0') {
                if (current.isEmpty()) {
                    break; // double NUL: end of list
                }
                segments.add(current.toString());
                current = new StringBuilder();
                if (!multi) {
                    break;
                }
            } else {
                current.append(c);
            }
        }
        if (segments.size() <= 1) {
            return List.copyOf(segments);
        }
        String directory = segments.get(0);
        List<String> paths = new ArrayList<>();
        for (int i = 1; i < segments.size(); i++) {
            paths.add(directory.endsWith("\\") ? directory + segments.get(i)
                    : directory + "\\" + segments.get(i));
        }
        return paths;
    }
}
