package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.ffm.CallbackGate;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.webview.spi.FileWatchBackend;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * macOS FSEvents file-watching backend (public CoreServices API through FFM). One stream
 * per watch, delivered on a dedicated serial dispatch queue — never the main thread — so a
 * busy filesystem never stalls the UI. The callback stub is pinned by a
 * {@link NativeCallbackRegistry} and gated so a late event after {@code close()} is a
 * no-op. Sub-100&nbsp;ms, event-driven — the reason the portable polling WatchService is
 * only a fallback here.
 */
final class MacFsEventsBackend implements FileWatchBackend {
    private static final Logger LOG = System.getLogger(MacFsEventsBackend.class.getName());

    private static final Arena LIB_ARENA = Arena.ofShared();
    private static final SymbolLookup CORE_SERVICES = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreServices.framework/CoreServices", LIB_ARENA);
    private static final SymbolLookup CORE_FOUNDATION = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", LIB_ARENA);

    // FSEventStreamCreateFlags
    private static final int CREATE_FLAG_NO_DEFER = 0x00000002;
    private static final int CREATE_FLAG_FILE_EVENTS = 0x00000010;
    private static final int CREATE_FLAGS = CREATE_FLAG_NO_DEFER | CREATE_FLAG_FILE_EVENTS;
    private static final long SINCE_NOW = -1L; // kFSEventStreamEventIdSinceNow (UINT64_MAX)
    private static final int UTF8 = 0x08000100; // kCFStringEncodingUTF8

    // FSEventStreamEventFlags (delivered per event)
    private static final int EV_MUST_SCAN_SUBDIRS = 0x00000001;
    private static final int EV_USER_DROPPED = 0x00000002;
    private static final int EV_KERNEL_DROPPED = 0x00000004;
    private static final int EV_ITEM_CREATED = 0x00000100;
    private static final int EV_ITEM_REMOVED = 0x00000200;
    private static final int EV_ITEM_INODE_META = 0x00000400;
    private static final int EV_ITEM_MODIFIED = 0x00001000;
    private static final int EV_DROPPED = EV_MUST_SCAN_SUBDIRS | EV_USER_DROPPED | EV_KERNEL_DROPPED;

    private static final MethodHandle FS_CREATE = down(CORE_SERVICES, "FSEventStreamCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG,
                    JAVA_DOUBLE, JAVA_INT));
    private static final MethodHandle FS_SET_QUEUE = down(CORE_SERVICES,
            "FSEventStreamSetDispatchQueue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle FS_START = down(CORE_SERVICES, "FSEventStreamStart",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS));
    private static final MethodHandle FS_STOP = down(CORE_SERVICES, "FSEventStreamStop",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle FS_INVALIDATE = down(CORE_SERVICES,
            "FSEventStreamInvalidate", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle FS_RELEASE = down(CORE_SERVICES, "FSEventStreamRelease",
            FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle CF_STRING = down(CORE_FOUNDATION,
            "CFStringCreateWithCString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CF_ARRAY = down(CORE_FOUNDATION, "CFArrayCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle CF_RELEASE = down(CORE_FOUNDATION, "CFRelease",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MemorySegment CF_TYPE_ARRAY_CALLBACKS = CORE_FOUNDATION
            .find("kCFTypeArrayCallBacks")
            .orElseThrow(() -> new IllegalStateException("kCFTypeArrayCallBacks not found"));

    private static final MethodHandle DISPATCH_QUEUE_CREATE = down(ObjC.SYSTEM,
            "dispatch_queue_create", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle DISPATCH_RELEASE = ObjC.SYSTEM.find("dispatch_release")
            .map(sym -> ObjC.LINKER.downcallHandle(sym, FunctionDescriptor.ofVoid(ADDRESS)))
            .orElse(null);

    private static final FunctionDescriptor CALLBACK_DESC = FunctionDescriptor.ofVoid(
            ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle ON_EVENTS;

    static {
        try {
            ON_EVENTS = MethodHandles.lookup().findVirtual(Stream.class, "onEvents",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                            long.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Watch watch(Path root, boolean recursive, Consumer<FileWatchEvent> sink)
            throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            return new Stream(normalized, recursive, sink);
        } catch (RuntimeException e) {
            throw new IOException("Failed to start FSEvents watch for " + normalized, e);
        }
    }

    /** One FSEvents stream + its serial dispatch queue and pinned callback. */
    private static final class Stream implements Watch {
        private final Path root;
        private final boolean recursive;
        private final Consumer<FileWatchEvent> sink;
        private final NativeCallbackRegistry registry;
        private final CallbackGate gate;
        private final MemorySegment stream;
        private final MemorySegment queue;
        private volatile boolean closed;

        Stream(Path root, boolean recursive, Consumer<FileWatchEvent> sink) {
            this.root = root;
            this.recursive = recursive;
            this.sink = sink;
            this.registry = new NativeCallbackRegistry("fsevents:" + root, Arena.ofShared());
            this.gate = registry.gate();

            MethodHandle bound = ON_EVENTS.bindTo(this);
            MemorySegment callback = ObjC.LINKER.upcallStub(bound, CALLBACK_DESC, registry.arena());

            MemorySegment createdStream;
            MemorySegment createdQueue;
            try (Arena setup = Arena.ofConfined()) {
                MemorySegment cfPath = (MemorySegment) CF_STRING.invokeExact(MemorySegment.NULL,
                        setup.allocateFrom(root.toString()), UTF8);
                if (cfPath.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("CFStringCreateWithCString returned null");
                }
                MemorySegment values = setup.allocate(ADDRESS);
                values.set(ADDRESS, 0, cfPath);
                MemorySegment paths = (MemorySegment) CF_ARRAY.invokeExact(MemorySegment.NULL,
                        values, 1L, CF_TYPE_ARRAY_CALLBACKS);

                createdStream = (MemorySegment) FS_CREATE.invokeExact(MemorySegment.NULL, callback,
                        MemorySegment.NULL, paths, SINCE_NOW, 0.0d, CREATE_FLAGS);

                CF_RELEASE.invokeExact(paths);
                CF_RELEASE.invokeExact(cfPath);

                if (createdStream.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("FSEventStreamCreate returned null");
                }
                createdQueue = (MemorySegment) DISPATCH_QUEUE_CREATE.invokeExact(
                        setup.allocateFrom("dev.jdesk.fsevents"), MemorySegment.NULL);

                FS_SET_QUEUE.invokeExact(createdStream, createdQueue);
                byte started = (byte) FS_START.invokeExact(createdStream);
                if (started == 0) {
                    FS_INVALIDATE.invokeExact(createdStream);
                    FS_RELEASE.invokeExact(createdStream);
                    releaseQueue(createdQueue);
                    throw new IllegalStateException("FSEventStreamStart failed");
                }
            } catch (Throwable t) {
                registry.close();
                throw ObjC.rethrow(t);
            }
            this.stream = createdStream;
            this.queue = createdQueue;
            registry.register(new NativeCallbackRegistry.Registration(
                    "fsevents:" + root, this, bound, callback, null, () -> { }));
        }

        /** FSEvents callback (dispatch-queue thread): decode char** paths + UInt32 flags. */
        @SuppressWarnings("unused") // invoked from native via the upcall stub
        void onEvents(MemorySegment streamRef, MemorySegment info, long numEvents,
                MemorySegment eventPaths, MemorySegment eventFlags, MemorySegment eventIds) {
            if (!gate.enter()) {
                return;
            }
            try {
                MemorySegment paths = eventPaths.reinterpret(numEvents * ADDRESS.byteSize());
                MemorySegment flags = eventFlags.reinterpret(numEvents * JAVA_INT.byteSize());
                for (long i = 0; i < numEvents; i++) {
                    MemorySegment cstr = paths.getAtIndex(ADDRESS, i);
                    if (cstr.equals(MemorySegment.NULL)) {
                        continue;
                    }
                    String path = cstr.reinterpret(Long.MAX_VALUE).getString(0);
                    deliver(path, flags.getAtIndex(JAVA_INT, i));
                }
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "FSEvents callback failed for " + root, t);
            } finally {
                gate.exit();
            }
        }

        private void deliver(String rawPath, int flags) {
            if ((flags & EV_DROPPED) != 0) {
                sink.accept(FileWatchEvent.overflow(root));
                return;
            }
            Path path = Path.of(rawPath);
            if (!recursive && !root.equals(path.getParent())) {
                return; // FSEvents is always recursive; filter to direct children when asked
            }
            // FSEvents coalesces flags cumulatively per path, so create vs. modify cannot be
            // cleanly separated from flags alone. Current existence keeps DELETED reliable;
            // the create/modify split is best-effort (documented).
            FileWatchEvent.Kind kind;
            if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                kind = FileWatchEvent.Kind.DELETED;
            } else if ((flags & EV_ITEM_CREATED) != 0
                    && (flags & (EV_ITEM_MODIFIED | EV_ITEM_INODE_META)) == 0) {
                kind = FileWatchEvent.Kind.CREATED;
            } else {
                kind = FileWatchEvent.Kind.MODIFIED;
            }
            sink.accept(new FileWatchEvent(path, kind));
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            try {
                FS_STOP.invokeExact(stream);
                FS_INVALIDATE.invokeExact(stream);
                FS_RELEASE.invokeExact(stream);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "FSEvents stream teardown failed for " + root, t);
            }
            registry.close(); // drains in-flight callbacks, then frees the pinned stub
            releaseQueue(queue);
        }

        private static void releaseQueue(MemorySegment queue) {
            try {
                if (DISPATCH_RELEASE != null) {
                    DISPATCH_RELEASE.invokeExact(queue);
                } else {
                    ObjC.release(queue); // dispatch objects are ObjC objects under OS_OBJECT_USE_OBJC
                }
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "dispatch queue release failed", t);
            }
        }
    }

    private static MethodHandle down(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return ObjC.LINKER.downcallHandle(
                lib.find(name).orElseThrow(() -> new IllegalStateException("symbol not found: " + name)),
                descriptor);
    }
}
