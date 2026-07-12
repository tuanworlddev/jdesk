package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import dev.jdesk.api.PtySpec;
import dev.jdesk.webview.spi.PtyBackend;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Linux pseudo-terminal backend: {@code openpty} (libutil) + {@code posix_spawn} (glibc, no
 * fork), the POSIX sibling of the macOS backend. {@code POSIX_SPAWN_SETSID} makes the child a
 * session leader and the file actions open the pts slave by name as fd 0/1/2, giving it a real
 * controlling terminal with job control. Reader/waiter run on platform daemon threads; signals
 * target the process group so nothing is orphaned.
 *
 * <p>Compile-verified on the build host; runtime-verified on the Linux CI lane (there is no
 * Linux environment on the authoring machine). Constants and struct sizes are glibc/Linux
 * ABI, distinct from the macOS backend.
 */
final class LinuxPtyBackend implements PtyBackend {
    private static final Logger LOG = System.getLogger(LinuxPtyBackend.class.getName());

    private static final int O_RDWR = 0x0002;
    private static final short POSIX_SPAWN_SETSID = 0x80; // glibc <spawn.h>
    private static final long TIOCSWINSZ = 0x5414L;       // Linux asm-generic/ioctls.h
    private static final int READ_CHUNK = 64 * 1024;
    private static final long KILL_GRACE_NANOS = 3_000_000_000L;
    // Generous over-allocation: glibc posix_spawn_file_actions_t ~80 B, posix_spawnattr_t ~336 B.
    private static final long FILE_ACTIONS_SIZE = 128;
    private static final long ATTR_SIZE = 512;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIB_ARENA = Arena.ofShared();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final SymbolLookup UTIL =
            SymbolLookup.libraryLookup("libutil.so.1", LIB_ARENA).or(LIBC);

    private static final MethodHandle OPENPTY = down(UTIL, "openpty",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle POSIX_SPAWNP = down(LIBC, "posix_spawnp",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FA_INIT = down(LIBC, "posix_spawn_file_actions_init",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FA_DESTROY = down(LIBC, "posix_spawn_file_actions_destroy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FA_ADDOPEN = down(LIBC, "posix_spawn_file_actions_addopen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FA_ADDDUP2 = down(LIBC, "posix_spawn_file_actions_adddup2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle FA_ADDCHDIR = LIBC
            .find("posix_spawn_file_actions_addchdir_np")
            .map(sym -> LINKER.downcallHandle(sym,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)))
            .orElse(null);
    private static final MethodHandle ATTR_INIT = down(LIBC, "posix_spawnattr_init",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ATTR_DESTROY = down(LIBC, "posix_spawnattr_destroy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle ATTR_SETFLAGS = down(LIBC, "posix_spawnattr_setflags",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT));
    private static final MethodHandle IOCTL = LINKER.downcallHandle(
            LIBC.findOrThrow("ioctl"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
            Linker.Option.firstVariadicArg(2));
    private static final MethodHandle WAITPID = down(LIBC, "waitpid",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle KILL = down(LIBC, "kill",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle READ = down(LIBC, "read",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle WRITE = down(LIBC, "write",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle CLOSE = down(LIBC, "close",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    @Override
    public Session open(PtySpec spec, Consumer<byte[]> output) throws IOException {
        Path normalized = spec.workingDirectory().map(p -> p.toAbsolutePath().normalize())
                .orElse(null);
        int master;
        int slave;
        String slaveName;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment masterFd = arena.allocate(JAVA_INT);
            MemorySegment slaveFd = arena.allocate(JAVA_INT);
            MemorySegment nameBuf = arena.allocate(1024);
            int rc = (int) OPENPTY.invokeExact(masterFd, slaveFd, nameBuf,
                    MemorySegment.NULL, winsize(arena, spec.columns(), spec.rows()));
            if (rc != 0) {
                throw new IOException("openpty failed (rc=" + rc + ")");
            }
            master = masterFd.get(JAVA_INT, 0);
            slave = slaveFd.get(JAVA_INT, 0);
            slaveName = nameBuf.getString(0);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("openpty failed", t);
        }

        int pid;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fileActions = arena.allocate(FILE_ACTIONS_SIZE);
            MemorySegment attr = arena.allocate(ATTR_SIZE);
            check((int) ATTR_INIT.invokeExact(attr), "posix_spawnattr_init");
            check((int) ATTR_SETFLAGS.invokeExact(attr, POSIX_SPAWN_SETSID), "setflags");
            check((int) FA_INIT.invokeExact(fileActions), "file_actions_init");
            if (normalized != null && FA_ADDCHDIR != null) {
                check((int) FA_ADDCHDIR.invokeExact(fileActions,
                        arena.allocateFrom(normalized.toString())), "addchdir");
            }
            check((int) FA_ADDDUP2.invokeExact(fileActions, master, master), "keep master");
            check((int) FA_ADDOPEN.invokeExact(fileActions, 0,
                    arena.allocateFrom(slaveName), O_RDWR, 0), "addopen slave");
            check((int) FA_ADDDUP2.invokeExact(fileActions, 0, 1), "dup2 stdout");
            check((int) FA_ADDDUP2.invokeExact(fileActions, 0, 2), "dup2 stderr");

            MemorySegment argv = cStringArray(arena, spec.command());
            MemorySegment envp = cStringArray(arena, environment(spec));
            MemorySegment pidOut = arena.allocate(JAVA_INT);
            MemorySegment path = arena.allocateFrom(spec.command().get(0));

            int rc = (int) POSIX_SPAWNP.invokeExact(pidOut, path, fileActions, attr, argv, envp);
            int unusedFa = (int) FA_DESTROY.invokeExact(fileActions);
            int unusedAttr = (int) ATTR_DESTROY.invokeExact(attr);
            if (rc != 0) {
                closeFd(master);
                closeFd(slave);
                throw new IOException("posix_spawnp failed for '" + spec.command().get(0)
                        + "' (errno=" + rc + ")");
            }
            pid = pidOut.get(JAVA_INT, 0);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            closeFd(master);
            closeFd(slave);
            throw new IOException("posix_spawnp failed", t);
        }

        closeFd(slave);
        return new PtySession(master, pid, output);
    }

    private static MemorySegment winsize(Arena arena, int columns, int rows) {
        MemorySegment ws = arena.allocate(8);
        ws.set(JAVA_SHORT, 0, (short) rows);
        ws.set(JAVA_SHORT, 2, (short) columns);
        ws.set(JAVA_SHORT, 4, (short) 0);
        ws.set(JAVA_SHORT, 6, (short) 0);
        return ws;
    }

    private static List<String> environment(PtySpec spec) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.putAll(spec.environment());
        env.putIfAbsent("TERM", "xterm-256color");
        return env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
    }

    private static MemorySegment cStringArray(Arena arena, List<String> values) {
        MemorySegment array = arena.allocate(ADDRESS.byteSize() * (values.size() + 1));
        for (int i = 0; i < values.size(); i++) {
            array.setAtIndex(ADDRESS, i, arena.allocateFrom(values.get(i)));
        }
        array.setAtIndex(ADDRESS, values.size(), MemorySegment.NULL);
        return array;
    }

    private static void check(int rc, String what) throws IOException {
        if (rc != 0) {
            throw new IOException(what + " failed (errno=" + rc + ")");
        }
    }

    private static void closeFd(int fd) {
        try {
            int unused = (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "close(fd) failed", t);
        }
    }

    private static final class PtySession implements Session {
        private final int master;
        private final int pid;
        private final Consumer<byte[]> output;
        private final AtomicBoolean exited = new AtomicBoolean();
        private final AtomicInteger exitStatus = new AtomicInteger();
        private final AtomicBoolean hasExitStatus = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<Runnable> exitListeners = new CopyOnWriteArrayList<>();

        PtySession(int master, int pid, Consumer<byte[]> output) {
            this.master = master;
            this.pid = pid;
            this.output = output;
            Thread.ofPlatform().daemon().name("jdesk-pty-reader-" + pid).start(this::readLoop);
            Thread.ofPlatform().daemon().name("jdesk-pty-waiter-" + pid).start(this::waitLoop);
        }

        private void readLoop() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(READ_CHUNK);
                while (true) {
                    long n = (long) READ.invokeExact(master, buffer, (long) READ_CHUNK);
                    if (n <= 0) {
                        return;
                    }
                    byte[] chunk = buffer.asSlice(0, n).toArray(JAVA_BYTE);
                    try {
                        output.accept(chunk);
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "PTY output consumer threw", e);
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "PTY reader ended", t);
            }
        }

        private void waitLoop() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment status = arena.allocate(JAVA_INT);
                int r;
                int attempts = 0;
                do {
                    r = (int) WAITPID.invokeExact(pid, status, 0);
                } while (r == -1 && ++attempts < 100);
                exitStatus.set(decodeExit(status.get(JAVA_INT, 0)));
                hasExitStatus.set(true);
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "PTY waiter ended", t);
            } finally {
                exited.set(true);
                closeFd(master);
                for (Runnable listener : exitListeners) {
                    safeRun(listener);
                }
            }
        }

        private static int decodeExit(int status) {
            int low = status & 0x7f;
            if (low == 0) {
                return (status >> 8) & 0xff;
            }
            if (low == 0x7f) {
                return -1;
            }
            return 128 + low;
        }

        @Override
        public void write(byte[] data) {
            if (data.length == 0 || exited.get()) {
                return;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(data.length);
                MemorySegment.copy(data, 0, buffer, JAVA_BYTE, 0, data.length);
                long offset = 0;
                while (offset < data.length) {
                    long n = (long) WRITE.invokeExact(master, buffer.asSlice(offset),
                            data.length - offset);
                    if (n <= 0) {
                        return;
                    }
                    offset += n;
                }
            } catch (Throwable t) {
                throw Gtk.rethrow(t);
            }
        }

        @Override
        public void resize(int columns, int rows) {
            if (exited.get()) {
                return;
            }
            try (Arena arena = Arena.ofConfined()) {
                int unused = (int) IOCTL.invokeExact(master, TIOCSWINSZ,
                        winsize(arena, columns, rows));
            } catch (Throwable t) {
                throw Gtk.rethrow(t);
            }
        }

        @Override
        public boolean isAlive() {
            return !exited.get();
        }

        @Override
        public OptionalInt exitCode() {
            return hasExitStatus.get() ? OptionalInt.of(exitStatus.get()) : OptionalInt.empty();
        }

        @Override
        public void sendSignal(int signal) {
            try {
                int unused = (int) KILL.invokeExact(-pid, signal);
            } catch (Throwable t) {
                throw Gtk.rethrow(t);
            }
        }

        @Override
        public void onExit(Runnable listener) {
            exitListeners.add(listener);
            if (exited.get() && exitListeners.remove(listener)) {
                safeRun(listener);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true) || exited.get()) {
                return;
            }
            sendSignal(PtyBackend.SIGHUP);
            Thread.ofPlatform().daemon().name("jdesk-pty-reaper-" + pid).start(() -> {
                long deadline = System.nanoTime() + KILL_GRACE_NANOS;
                while (!exited.get() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!exited.get()) {
                    sendSignal(PtyBackend.SIGKILL);
                }
            });
        }

        private static void safeRun(Runnable runnable) {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "PTY exit listener threw", e);
            }
        }
    }

    private static MethodHandle down(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(lookup.findOrThrow(name), desc);
    }
}
