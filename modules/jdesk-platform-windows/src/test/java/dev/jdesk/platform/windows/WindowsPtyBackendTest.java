package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.PtySpec;
import dev.jdesk.webview.spi.PtyBackend;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Headless ConPTY backend tests — no JDeskRuntime, COM, or WebView2 window. Windows-only
 * (they spawn {@code cmd.exe}); skipped on the Linux unit lane. The exit-code path exercises
 * {@code GetExitCodeProcess} on the live process handle, which regression-guards the
 * process-handle double-close fix.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsPtyBackendTest {

    @Test
    void propagatesExitCode() throws Exception {
        WindowsPtyBackend backend = new WindowsPtyBackend();
        PtyBackend.Session session = backend.open(
                new PtySpec(List.of("cmd.exe", "/c", "exit 5"), Optional.empty(), Map.of(), 80, 24),
                chunk -> { });
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (session.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(30);
        }
        assertThat(session.isAlive()).isFalse();
        assertThat(session.exitCode()).hasValue(5);
        session.close();
    }

    @Test
    void killTerminatesTheChild() throws Exception {
        WindowsPtyBackend backend = new WindowsPtyBackend();
        PtyBackend.Session session = backend.open(
                new PtySpec(List.of("cmd.exe", "/c", "ping -n 20 127.0.0.1"),
                        Optional.empty(), Map.of(), 80, 24),
                chunk -> { });
        Thread.sleep(300);
        assertThat(session.isAlive()).isTrue();
        session.sendSignal(PtyBackend.SIGKILL);
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (session.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(session.isAlive()).isFalse();
        session.close();
    }

    @Test
    void resizeDoesNotThrow() throws Exception {
        WindowsPtyBackend backend = new WindowsPtyBackend();
        PtyBackend.Session session = backend.open(
                new PtySpec(List.of("cmd.exe", "/c", "ping -n 10 127.0.0.1"),
                        Optional.empty(), Map.of(), 80, 24),
                chunk -> { });
        Thread.sleep(200);
        session.resize(100, 40); // must not throw on a live pseudoconsole
        session.sendSignal(PtyBackend.SIGKILL);
        session.close();
    }
}
