package dev.jdesk.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Windows pseudo-terminal backend using the ConPTY API ({@code CreatePseudoConsole} +
 * {@code STARTUPINFOEX}/{@code CreateProcessW}), the Windows equivalent of a PTY. Two anonymous
 * pipes carry input/output; the child is launched attached to the pseudoconsole via a
 * proc-thread attribute. Reader/waiter run on platform daemon threads. Windows has no POSIX
 * signals, so {@code terminate}/{@code kill}/{@code close} use {@code TerminateProcess} (after
 * {@code ClosePseudoConsole}, which asks the child to exit).
 *
 * <p>Runtime status (Windows 11, {@code WindowsPtyProbe}): opening a child, streamed output,
 * a propagated exit code, resize and kill are all verified. Small structs are passed as packed
 * {@code JAVA_INT}s (COORD) and pointer-bearing buffers are 8-byte aligned. <b>Known open
 * issue:</b> an interactive shell (e.g. bare {@code cmd.exe}) is not fully isolated in the
 * pseudoconsole — the child inherits the parent console and a {@code write()} does not reach
 * its stdin, even though every {@code CreateProcessW} input (valid HPCON, {@code cb} = 112,
 * attribute list, {@code EXTENDED_STARTUPINFO_PRESENT}) is correct and matches the Microsoft
 * ConPTY sample. Running a command and reading its output works; a live REPL does not yet.
 * ConPTY needs Windows 10 1809+.
 */
final class WindowsPtyBackend implements PtyBackend {
    private static final Logger LOG = System.getLogger(WindowsPtyBackend.class.getName());

    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
    private static final int INFINITE = 0xFFFFFFFF;
    private static final long STARTUPINFOW_SIZE = 104;      // x64 sizeof(STARTUPINFOW)
    private static final long STARTUPINFOEXW_SIZE = 112;    // + lpAttributeList (offset 104)
    private static final long PROCESS_INFORMATION_SIZE = 24; // hProcess,hThread,pid,tid
    private static final int READ_CHUNK = 64 * 1024;
    private static final long KILL_GRACE_NANOS = 3_000_000_000L;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIB_ARENA = Arena.ofShared();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", LIB_ARENA);

    private static final MethodHandle CREATE_PIPE = down("CreatePipe",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    // COORD is a 4-byte {SHORT X, SHORT Y} struct passed BY VALUE. The x64 ABI passes such a
    // small struct packed into a single 32-bit register, so we model it as a JAVA_INT
    // (X = low 16 bits, Y = high 16 bits). Passing it as a by-value struct layout made FFM
    // misplace the following handle arguments, so CreatePseudoConsole built a broken console
    // and the child fell back to inheriting the parent console.
    private static final MethodHandle CREATE_PSEUDO_CONSOLE = down("CreatePseudoConsole",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle RESIZE_PSEUDO_CONSOLE = down("ResizePseudoConsole",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private static int packCoord(int columns, int rows) {
        return (columns & 0xFFFF) | ((rows & 0xFFFF) << 16);
    }
    private static final MethodHandle CLOSE_PSEUDO_CONSOLE = down("ClosePseudoConsole",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle INIT_ATTR_LIST = down("InitializeProcThreadAttributeList",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle UPDATE_ATTR = down("UpdateProcThreadAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG,
                    ADDRESS, ADDRESS));
    private static final MethodHandle DELETE_ATTR_LIST = down("DeleteProcThreadAttributeList",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CREATE_PROCESS = down("CreateProcessW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT,
                    JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle READ_FILE = down("ReadFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WRITE_FILE = down("WriteFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WAIT_FOR_SINGLE = down("WaitForSingleObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_EXIT_CODE = down("GetExitCodeProcess",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle TERMINATE_PROCESS = down("TerminateProcess",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CLOSE_HANDLE = down("CloseHandle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    @Override
    public Session open(PtySpec spec, Consumer<byte[]> output) throws IOException {
        Arena arena = Arena.ofShared(); // owns the pseudoconsole + handles for the session
        try {
            MemorySegment inRead = arena.allocate(ADDRESS);
            MemorySegment inWrite = arena.allocate(ADDRESS);
            MemorySegment outRead = arena.allocate(ADDRESS);
            MemorySegment outWrite = arena.allocate(ADDRESS);
            checkBool((int) CREATE_PIPE.invokeExact(inRead, inWrite, MemorySegment.NULL, 0),
                    "CreatePipe(in)");
            checkBool((int) CREATE_PIPE.invokeExact(outRead, outWrite, MemorySegment.NULL, 0),
                    "CreatePipe(out)");
            MemorySegment inputRead = inRead.get(ADDRESS, 0);
            MemorySegment inputWrite = inWrite.get(ADDRESS, 0);
            MemorySegment outputRead = outRead.get(ADDRESS, 0);
            MemorySegment outputWrite = outWrite.get(ADDRESS, 0);

            MemorySegment hpcOut = arena.allocate(ADDRESS);
            int hr = (int) CREATE_PSEUDO_CONSOLE.invokeExact(
                    packCoord(spec.columns(), spec.rows()), inputRead, outputWrite, 0, hpcOut);
            if (hr < 0) {
                throw new IOException("CreatePseudoConsole failed (HRESULT 0x"
                        + Integer.toHexString(hr) + ")");
            }
            MemorySegment hpc = hpcOut.get(ADDRESS, 0);

            MemorySegment startupInfoEx = buildStartupInfoEx(arena, hpc);
            MemorySegment procInfo = arena.allocate(PROCESS_INFORMATION_SIZE, 8);
            MemorySegment commandLine = wide(arena, commandLine(spec));
            MemorySegment cwd = spec.workingDirectory()
                    .map(p -> wide(arena, p.toAbsolutePath().toString())).orElse(MemorySegment.NULL);

            int ok = (int) CREATE_PROCESS.invokeExact(MemorySegment.NULL, commandLine,
                    MemorySegment.NULL, MemorySegment.NULL, 0,
                    EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
                    MemorySegment.NULL, cwd, startupInfoEx, procInfo);
            if (ok == 0) {
                CLOSE_PSEUDO_CONSOLE.invokeExact(hpc);
                throw new IOException("CreateProcessW failed for " + spec.command().get(0));
            }
            MemorySegment process = procInfo.get(ADDRESS, 0);
            MemorySegment thread = procInfo.get(ADDRESS, 8);
            closeHandle(thread);
            // Close the pseudoconsole's ends only AFTER CreateProcessW has attached the child:
            // closing them earlier (before the child connects) leaves the child's stdin at EOF,
            // so an interactive shell exits immediately. The pseudoconsole keeps its own dups.
            closeHandle(inputRead);
            closeHandle(outputWrite);
            return new WinPtySession(arena, hpc, process, inputWrite, outputRead, output);
        } catch (IOException e) {
            arena.close();
            throw e;
        } catch (Throwable t) {
            arena.close();
            throw new IOException("ConPTY open failed", t);
        }
    }

    private static MemorySegment buildStartupInfoEx(Arena arena, MemorySegment hpc)
            throws Throwable {
        MemorySegment sizeOut = arena.allocate(JAVA_LONG);
        int unused = (int) INIT_ATTR_LIST.invokeExact(MemorySegment.NULL, 1, 0, sizeOut);
        long attrSize = sizeOut.get(JAVA_LONG, 0);
        // 8-byte alignment: the attribute list and STARTUPINFOEXW hold pointer-sized fields,
        // and the default 1-byte-aligned allocation can land on an odd address, corrupting the
        // list so CreateProcessW silently ignores the pseudoconsole attribute (the child then
        // inherits the parent console instead of attaching to the PTY).
        MemorySegment attrList = arena.allocate(Math.max(attrSize, 1), 8);
        checkBool((int) INIT_ATTR_LIST.invokeExact(attrList, 1, 0, sizeOut),
                "InitializeProcThreadAttributeList");
        checkBool((int) UPDATE_ATTR.invokeExact(attrList, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
                hpc, ADDRESS.byteSize(), MemorySegment.NULL, MemorySegment.NULL),
                "UpdateProcThreadAttribute");

        MemorySegment startupInfoEx = arena.allocate(STARTUPINFOEXW_SIZE, 8);
        startupInfoEx.set(JAVA_INT, 0, (int) STARTUPINFOEXW_SIZE);  // STARTUPINFOW.cb
        startupInfoEx.set(ADDRESS, STARTUPINFOW_SIZE, attrList);    // .lpAttributeList
        return startupInfoEx;
    }

    private static String commandLine(PtySpec spec) {
        StringBuilder sb = new StringBuilder();
        for (String part : spec.command()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part.contains(" ") ? '"' + part + '"' : part);
        }
        return sb.toString();
    }

    private static MemorySegment wide(Arena arena, String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment segment = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, segment, JAVA_BYTE, 0, utf16.length);
        return segment; // null terminator already zero
    }

    private static void checkBool(int result, String what) throws IOException {
        if (result == 0) {
            throw new IOException(what + " failed");
        }
    }

    private static void closeHandle(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            int unused = (int) CLOSE_HANDLE.invokeExact(handle);
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "CloseHandle failed", t);
        }
    }

    private static final class WinPtySession implements Session {
        private final Arena arena;
        private final MemorySegment hpc;
        private final MemorySegment process;
        private final MemorySegment inputWrite;
        private final MemorySegment outputRead;
        private final Consumer<byte[]> output;
        private final AtomicBoolean exited = new AtomicBoolean();
        private final AtomicInteger exitStatus = new AtomicInteger();
        private final AtomicBoolean hasExitStatus = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<Runnable> exitListeners = new CopyOnWriteArrayList<>();

        WinPtySession(Arena arena, MemorySegment hpc, MemorySegment process,
                MemorySegment inputWrite, MemorySegment outputRead, Consumer<byte[]> output) {
            this.arena = arena;
            this.hpc = hpc;
            this.process = process;
            this.inputWrite = inputWrite;
            this.outputRead = outputRead;
            this.output = output;
            Thread.ofPlatform().daemon().name("jdesk-conpty-reader").start(this::readLoop);
            Thread.ofPlatform().daemon().name("jdesk-conpty-waiter").start(this::waitLoop);
        }

        private void readLoop() {
            try (Arena io = Arena.ofConfined()) {
                MemorySegment buffer = io.allocate(READ_CHUNK);
                MemorySegment readOut = io.allocate(JAVA_INT);
                while (true) {
                    int ok = (int) READ_FILE.invokeExact(outputRead, buffer, READ_CHUNK,
                            readOut, MemorySegment.NULL);
                    int read = readOut.get(JAVA_INT, 0);
                    if (ok == 0 || read <= 0) {
                        return; // pipe closed / broken (child exited)
                    }
                    byte[] chunk = buffer.asSlice(0, read).toArray(JAVA_BYTE);
                    try {
                        output.accept(chunk);
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "PTY output consumer threw", e);
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "ConPTY reader ended", t);
            }
        }

        private void waitLoop() {
            try (Arena io = Arena.ofConfined()) {
                int unusedWait = (int) WAIT_FOR_SINGLE.invokeExact(process, INFINITE);
                MemorySegment codeOut = io.allocate(JAVA_INT);
                int ok = (int) GET_EXIT_CODE.invokeExact(process, codeOut);
                exitStatus.set(ok != 0 ? codeOut.get(JAVA_INT, 0) : -1);
                hasExitStatus.set(true);
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "ConPTY waiter ended", t);
            } finally {
                exited.set(true);
                for (Runnable listener : exitListeners) {
                    safeRun(listener);
                }
            }
        }

        @Override
        public void write(byte[] data) {
            if (data.length == 0 || exited.get()) {
                return;
            }
            try (Arena io = Arena.ofConfined()) {
                MemorySegment buffer = io.allocate(data.length);
                MemorySegment.copy(data, 0, buffer, JAVA_BYTE, 0, data.length);
                MemorySegment writtenOut = io.allocate(JAVA_INT);
                int off = 0;
                while (off < data.length) {
                    int ok = (int) WRITE_FILE.invokeExact(inputWrite, buffer.asSlice(off),
                            data.length - off, writtenOut, MemorySegment.NULL);
                    int written = writtenOut.get(JAVA_INT, 0);
                    if (ok == 0 || written <= 0) {
                        return;
                    }
                    off += written;
                }
            } catch (Throwable t) {
                throw new IllegalStateException("ConPTY write failed", t);
            }
        }

        @Override
        public void resize(int columns, int rows) {
            if (exited.get()) {
                return;
            }
            try {
                int unused = (int) RESIZE_PSEUDO_CONSOLE.invokeExact(hpc, packCoord(columns, rows));
            } catch (Throwable t) {
                throw new IllegalStateException("ConPTY resize failed", t);
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
            // No POSIX signals on Windows: terminate the child (there is no SIGHUP equivalent).
            try {
                int unused = (int) TERMINATE_PROCESS.invokeExact(process, 1);
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "TerminateProcess failed", t);
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
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                CLOSE_PSEUDO_CONSOLE.invokeExact(hpc); // asks the child to exit
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "ClosePseudoConsole failed", t);
            }
            Thread.ofPlatform().daemon().name("jdesk-conpty-reaper").start(() -> {
                long deadline = System.nanoTime() + KILL_GRACE_NANOS;
                while (!exited.get() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!exited.get()) {
                    sendSignal(PtyBackend.SIGKILL);
                }
                closeHandle(inputWrite);
                closeHandle(outputRead);
                closeHandle(process);
                arena.close();
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

    private static MethodHandle down(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(KERNEL32.findOrThrow(name), descriptor);
    }
}
