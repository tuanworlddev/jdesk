package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Headless Win32 clipboard round-trip (Windows-only; {@code OpenClipboard(NULL)} needs no
 * window). The system clipboard is shared, so if another process holds it the test is skipped
 * rather than failed.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsClipboardTest {

    @Test
    void writeThenReadRoundTripsUnicode() {
        String marker = "jdesk-clip-테스트-🔧-" + System.nanoTime();
        try {
            Win32.writeClipboardText(marker);
        } catch (RuntimeException e) {
            abort("clipboard unavailable (held by another process): " + e.getMessage());
        }
        // Small retry: the clipboard can be transiently locked between the write and read.
        RuntimeException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                assertThat(Win32.readClipboardText()).isEqualTo(marker);
                return;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        abort("clipboard read unavailable: " + (last == null ? "?" : last.getMessage()));
    }

    @Test
    void emptyStringRoundTrips() {
        try {
            Win32.writeClipboardText("");
            assertThat(Win32.readClipboardText()).isEqualTo("");
        } catch (RuntimeException e) {
            abort("clipboard unavailable: " + e.getMessage());
        }
    }
}
