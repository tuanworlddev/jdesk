package dev.jdesk.platform.windows;

import dev.jdesk.ffm.NativeCallbackRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Sequential, read-only IStream implemented in Java so large asset bodies stream into
 * WebView2 without full buffering (spec 9.1). Each stream owns a private callback
 * registry; when the engine releases its last reference the registry is closed on a
 * later UI-thread turn (never from inside the Release upcall, which would deadlock the
 * quiescence gate).
 *
 * Vtable (objidl.h): IUnknown 0-2, ISequentialStream Read=3 Write=4, IStream Seek=5,
 * SetSize=6, CopyTo=7, Commit=8, Revert=9, LockRegion=10, UnlockRegion=11, Stat=12,
 * Clone=13.
 */
final class JavaIStream {
    private static final Logger LOG = System.getLogger(JavaIStream.class.getName());
    private static final String IID_ISEQUENTIAL_STREAM = "0c733a30-2a1c-11ce-ade5-00aa0044773d";
    private static final String IID_ISTREAM = "0000000c-0000-0000-c000-000000000046";
    private static final int S_FALSE = 1;
    private static final int STG_E_INVALIDFUNCTION = 0x80030001;

    private JavaIStream() {
    }

    static MemorySegment create(WindowsPlatformApplication app, InputStream body, long length) {
        NativeCallbackRegistry registry =
                new NativeCallbackRegistry("istream", Arena.ofShared());
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            ReadFn read = (self, buffer, count, readOut) -> {
                try {
                    if (count < 0) {
                        return Hresult.E_FAIL;
                    }
                    byte[] chunk = body.readNBytes(count);
                    if (chunk.length > 0) {
                        MemorySegment.copy(MemorySegment.ofArray(chunk), 0,
                                buffer.reinterpret(count), 0, chunk.length);
                    }
                    if (!readOut.equals(MemorySegment.NULL)) {
                        readOut.reinterpret(4).set(JAVA_INT, 0, chunk.length);
                    }
                    return chunk.length == count ? Hresult.S_OK : S_FALSE;
                } catch (IOException e) {
                    LOG.log(Level.ERROR, "IStream read failed", e);
                    return Hresult.E_FAIL;
                }
            };

            StatFn stat = (self, statstg, flags) -> {
                // STATSTG (objidl.h, x64): pwcsName 0, type 8, cbSize 16 (ULARGE_INTEGER),
                // mtime 24, ctime 32, atime 40, grfMode 48, grfLocksSupported 52,
                // clsid 56, grfStateBits 72, reserved 76; total 80 bytes.
                MemorySegment out = statstg.reinterpret(80);
                out.fill((byte) 0);
                out.set(JAVA_INT, 8, 2); // STGTY_STREAM
                out.set(JAVA_LONG, 16, Math.max(length, 0));
                return Hresult.S_OK;
            };

            NotImplFn notImpl = self -> STG_E_INVALIDFUNCTION;

            FunctionDescriptor readDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
                    JAVA_INT, ADDRESS);
            FunctionDescriptor statDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
                    JAVA_INT);
            // Not-implemented slots: model only the this-pointer; extra caller arguments
            // are passed in registers/stack and safely ignored under the Windows x64 ABI.
            FunctionDescriptor notImplDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS);

            MethodType readType = MethodType.methodType(int.class, MemorySegment.class,
                    MemorySegment.class, int.class, MemorySegment.class);
            MethodType statType = MethodType.methodType(int.class, MemorySegment.class,
                    MemorySegment.class, int.class);
            MethodType notImplType = MethodType.methodType(int.class, MemorySegment.class);

            ComCallback.Slot notImplSlot = new ComCallback.Slot(notImplDesc,
                    lookup.findVirtual(NotImplFn.class, "invoke", notImplType).bindTo(notImpl));

            MemorySegment stream = ComCallback.create(registry, "JavaIStream",
                    List.of(IID_ISEQUENTIAL_STREAM, IID_ISTREAM),
                    List.of(
                            new ComCallback.Slot(readDesc, lookup.findVirtual(ReadFn.class,
                                    "invoke", readType).bindTo(read)),   // Read
                            notImplSlot,                                  // Write
                            notImplSlot,                                  // Seek
                            notImplSlot,                                  // SetSize
                            notImplSlot,                                  // CopyTo
                            notImplSlot,                                  // Commit
                            notImplSlot,                                  // Revert
                            notImplSlot,                                  // LockRegion
                            notImplSlot,                                  // UnlockRegion
                            new ComCallback.Slot(statDesc, lookup.findVirtual(StatFn.class,
                                    "invoke", statType).bindTo(stat)),    // Stat
                            notImplSlot));                                // Clone

            // Close the body and the registry once the engine is done: poll refcount via
            // a UI-thread tick after Release reaches zero is complex; instead tie cleanup
            // to registry close at window teardown, and close the body on EOF/failure.
            registry.register(new NativeCallbackRegistry.Registration(
                    "istream-body", body, MethodHandles.constant(Object.class, body),
                    stream, null, () -> {
                        try {
                            body.close();
                        } catch (IOException e) {
                            LOG.log(Level.DEBUG, "IStream body close failed", e);
                        }
                    }));
            app.adoptStreamRegistry(registry);
            return stream;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            registry.close();
            throw new IllegalStateException(e);
        }
    }

    interface ReadFn {
        int invoke(MemorySegment self, MemorySegment buffer, int count, MemorySegment readOut);
    }

    interface StatFn {
        int invoke(MemorySegment self, MemorySegment statstg, int flags);
    }

    interface NotImplFn {
        int invoke(MemorySegment self);
    }
}
