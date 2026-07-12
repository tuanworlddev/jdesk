package dev.jdesk.runtime.pty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PtyHandle;
import dev.jdesk.api.PtySpec;
import dev.jdesk.webview.spi.PtyBackend;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Session cap, tracking, auto-removal, close-all and signal delegation of the PTY manager. */
class PtyManagerTest {

    private static final PtySpec SPEC = PtySpec.of("/bin/echo", "hi");

    private static final class FakeBackend implements PtyBackend {
        final CopyOnWriteArrayList<FakeSession> sessions = new CopyOnWriteArrayList<>();

        @Override
        public Session open(PtySpec spec, Consumer<byte[]> output) {
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }
    }

    private static final class FakeSession implements PtyBackend.Session {
        volatile boolean alive = true;
        volatile boolean closed;
        volatile int lastSignal;
        private Runnable onExit;

        @Override public void write(byte[] data) { }

        @Override public void resize(int columns, int rows) { }

        @Override public boolean isAlive() { return alive; }

        @Override public OptionalInt exitCode() {
            return alive ? OptionalInt.empty() : OptionalInt.of(0);
        }

        @Override public void sendSignal(int signal) { lastSignal = signal; }

        @Override public void onExit(Runnable listener) { this.onExit = listener; }

        @Override public void close() { closed = true; }

        void simulateExit() {
            alive = false;
            if (onExit != null) {
                onExit.run();
            }
        }
    }

    @Test
    void opensTracksAndDelegatesSignals() {
        FakeBackend backend = new FakeBackend();
        try (PtyManager manager = new PtyManager(backend)) {
            PtyHandle handle = manager.open(SPEC, out -> { });
            assertThat(manager.activeSessionCount()).isEqualTo(1);
            assertThat(handle.isAlive()).isTrue();
            assertThat(handle.exitCode()).isEmpty();

            handle.terminate();
            assertThat(backend.sessions.getFirst().lastSignal).isEqualTo(PtyBackend.SIGHUP);
            handle.kill();
            assertThat(backend.sessions.getFirst().lastSignal).isEqualTo(PtyBackend.SIGKILL);

            handle.close();
            assertThat(backend.sessions.getFirst().closed).isTrue();
            assertThat(manager.activeSessionCount()).isZero();
        }
    }

    @Test
    void sessionCapIsEnforced() {
        try (PtyManager manager = new PtyManager(new FakeBackend())) {
            for (int i = 0; i < 64; i++) {
                manager.open(SPEC, out -> { });
            }
            assertThatThrownBy(() -> manager.open(SPEC, out -> { }))
                    .isInstanceOfSatisfying(JDeskException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        }
    }

    @Test
    void naturalExitRemovesTheSession() {
        FakeBackend backend = new FakeBackend();
        try (PtyManager manager = new PtyManager(backend)) {
            manager.open(SPEC, out -> { });
            assertThat(manager.activeSessionCount()).isEqualTo(1);

            backend.sessions.getFirst().simulateExit();
            assertThat(manager.activeSessionCount()).isZero();
        }
    }

    @Test
    void closeAllClosesEverySession() {
        FakeBackend backend = new FakeBackend();
        PtyManager manager = new PtyManager(backend);
        manager.open(SPEC, out -> { });
        manager.open(SPEC, out -> { });
        assertThat(manager.activeSessionCount()).isEqualTo(2);

        manager.close();
        assertThat(backend.sessions).allSatisfy(s -> assertThat(s.closed).isTrue());
        assertThat(manager.activeSessionCount()).isZero();
    }

    @Test
    void afterCloseOpenIsRejected() {
        PtyManager manager = new PtyManager(new FakeBackend());
        manager.close();
        assertThatThrownBy(() -> manager.open(SPEC, out -> { }))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }
}
