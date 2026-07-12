package dev.jdesk.platform.windows;

import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.FileDialog;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the comdlg32 filter string and the result-buffer parsing. These touch
 * only off-heap memory (no Win32 calls), so they run on every OS the module compiles on.
 */
class WindowsFileDialogTest {

    /** Reads a NUL-containing wide-char buffer back to a String with NULs shown as {@code |}. */
    private static String readChars(MemorySegment segment) {
        long count = segment.byteSize() / 2;
        StringBuilder out = new StringBuilder();
        for (long i = 0; i < count; i++) {
            char c = segment.getAtIndex(JAVA_CHAR, i);
            out.append(c == '\0' ? '|' : c);
        }
        return out.toString();
    }

    private static MemorySegment wideBuffer(Arena arena, String withNuls) {
        char[] chars = withNuls.toCharArray();
        MemorySegment seg = arena.allocate(JAVA_CHAR, chars.length);
        for (int i = 0; i < chars.length; i++) {
            seg.setAtIndex(JAVA_CHAR, i, chars[i]);
        }
        return seg;
    }

    @Test
    void buildFilterFormatsLabelsPatternsAndTerminators() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment filter = WindowsFileDialog.buildFilter(arena, List.of(
                    new FileDialog.Filter("Text", List.of("txt", "md")),
                    new FileDialog.Filter("All", List.of())));
            // label\0*.txt;*.md\0label\0*.*\0\0  (empty extension list -> *.*)
            assertThat(readChars(filter)).isEqualTo("Text|*.txt;*.md|All|*.*||");
        }
    }

    @Test
    void buildFilterReturnsNullForNoFilters() {
        try (Arena arena = Arena.ofConfined()) {
            assertThat(WindowsFileDialog.buildFilter(arena, List.of())).isNull();
        }
    }

    @Test
    void readSelectionSinglePath() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = wideBuffer(arena, "C:\\docs\\note.txt\0\0");
            assertThat(WindowsFileDialog.readSelection(buf, false))
                    .containsExactly("C:\\docs\\note.txt");
        }
    }

    @Test
    void readSelectionMultiSelectJoinsDirectoryAndFiles() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = wideBuffer(arena, "C:\\docs\0a.txt\0b.md\0\0");
            assertThat(WindowsFileDialog.readSelection(buf, true))
                    .containsExactly("C:\\docs\\a.txt", "C:\\docs\\b.md");
        }
    }

    @Test
    void readSelectionMultiSelectSingleFileHasNoSecondSegment() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = wideBuffer(arena, "C:\\docs\\only.txt\0\0");
            assertThat(WindowsFileDialog.readSelection(buf, true))
                    .containsExactly("C:\\docs\\only.txt");
        }
    }

    @Test
    void readSelectionEmptyBufferYieldsNothing() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = wideBuffer(arena, "\0\0");
            assertThat(WindowsFileDialog.readSelection(buf, false)).isEmpty();
        }
    }
}
