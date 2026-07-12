package dev.jdesk.runtime.pty;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PtyHandle;
import dev.jdesk.api.PtySpec;
import dev.jdesk.webview.spi.PtyBackend;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Owns PTY lifecycle on top of a {@link PtyBackend}: a session cap, tracking, auto-removal
 * when a child exits on its own, and closing every remaining session at shutdown so no child
 * is orphaned.
 */
public final class PtyManager implements AutoCloseable {
    private static final Logger LOG = System.getLogger(PtyManager.class.getName());
    private static final int MAX_SESSIONS = 64;

    private final PtyBackend backend;
    private final Set<ManagedHandle> handles = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public PtyManager(PtyBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** @return active PTY session count (test/diagnostic). */
    public int activeSessionCount() {
        return handles.size();
    }

    public PtyHandle open(PtySpec spec, Consumer<byte[]> output) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(output, "output");
        synchronized (this) {
            if (closed) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "PTY subsystem is shut down");
            }
            if (handles.size() >= MAX_SESSIONS) {
                throw new JDeskException(ErrorCode.LIMIT_EXCEEDED,
                        "Too many PTY sessions (max " + MAX_SESSIONS + ")");
            }
            PtyBackend.Session session;
            try {
                session = backend.open(spec, output);
            } catch (IOException e) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Failed to open PTY", null, e);
            }
            ManagedHandle handle = new ManagedHandle(session);
            handles.add(handle);
            // Auto-remove from tracking when the child exits by itself.
            session.onExit(() -> handles.remove(handle));
            return handle;
        }
    }

    @Override
    public void close() {
        List<ManagedHandle> toClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = List.copyOf(handles);
            handles.clear();
        }
        for (ManagedHandle handle : toClose) {
            handle.closeQuietly();
        }
    }

    private final class ManagedHandle implements PtyHandle {
        private final PtyBackend.Session session;

        ManagedHandle(PtyBackend.Session session) {
            this.session = session;
        }

        @Override
        public void write(byte[] data) {
            session.write(data);
        }

        @Override
        public void resize(int columns, int rows) {
            session.resize(columns, rows);
        }

        @Override
        public boolean isAlive() {
            return session.isAlive();
        }

        @Override
        public OptionalInt exitCode() {
            return session.exitCode();
        }

        @Override
        public void terminate() {
            session.sendSignal(PtyBackend.SIGHUP);
        }

        @Override
        public void kill() {
            session.sendSignal(PtyBackend.SIGKILL);
        }

        @Override
        public void close() {
            handles.remove(this);
            session.close();
        }

        void closeQuietly() {
            try {
                session.close();
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "PTY session close failed", e);
            }
        }
    }
}
