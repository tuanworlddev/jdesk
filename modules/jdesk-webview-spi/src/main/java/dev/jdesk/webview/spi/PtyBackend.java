package dev.jdesk.webview.spi;

import dev.jdesk.api.PtySpec;
import java.io.IOException;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Platform pseudo-terminal backend. The runtime owns session limits, lifecycle tracking and
 * close-all-at-shutdown; a backend only starts the child on a real TTY and exposes raw I/O,
 * resize, signalling, and exit status. Output is pushed to the callback given at
 * {@link #open} from a backend-owned reader thread.
 */
public interface PtyBackend {

    /** POSIX signal numbers a session may be sent. */
    int SIGHUP = 1;
    int SIGTERM = 15;
    int SIGKILL = 9;

    /** One live PTY child process. */
    interface Session extends AutoCloseable {
        /** Writes input bytes to the terminal master. */
        void write(byte[] data);

        /** Resizes the terminal (TIOCSWINSZ; the child gets SIGWINCH). */
        void resize(int columns, int rows);

        boolean isAlive();

        /** Exit status once exited (128+signal for signalled deaths), else empty. */
        OptionalInt exitCode();

        /** Sends {@code signal} to the child process. */
        void sendSignal(int signal);

        /** Registers a one-shot callback invoked once when the child exits. */
        void onExit(Runnable listener);

        /** SIGHUP then, after a grace period, SIGKILL of the process group; idempotent. */
        @Override
        void close();
    }

    /**
     * Starts the child on a fresh PTY.
     *
     * @param output receives terminal output chunks from a backend reader thread
     * @throws IOException if the PTY cannot be allocated or the child cannot be spawned
     */
    Session open(PtySpec spec, Consumer<byte[]> output) throws IOException;
}
