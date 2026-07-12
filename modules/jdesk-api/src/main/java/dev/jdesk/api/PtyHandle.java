package dev.jdesk.api;

import java.util.OptionalInt;

/**
 * A running pseudo-terminal child process (see {@link ApplicationHandle#openPty}). Output
 * is delivered to the callback passed at open; this handle is the input/control side. All
 * methods are safe from any thread. Closing sends SIGHUP and, if the process does not exit,
 * escalates to SIGKILL of its process group so nothing is orphaned.
 */
public interface PtyHandle extends AutoCloseable {

    /** Writes bytes to the terminal (keystrokes / paste). No-op once the child has exited. */
    void write(byte[] data);

    /** Updates the terminal window size; the child receives SIGWINCH. */
    void resize(int columns, int rows);

    /** @return true until the child process has exited. */
    boolean isAlive();

    /** @return the exit status once the child has exited, else empty. Signalled deaths report 128+signal. */
    OptionalInt exitCode();

    /** Requests graceful termination (SIGHUP). */
    void terminate();

    /** Forcibly kills the child and its process group (SIGKILL). */
    void kill();

    /** SIGHUP, then SIGKILL of the process group after a short grace period; idempotent. */
    @Override
    void close();
}
