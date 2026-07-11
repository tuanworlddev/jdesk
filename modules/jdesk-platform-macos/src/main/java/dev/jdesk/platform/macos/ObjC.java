package dev.jdesk.platform.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Objective-C runtime access through FFM. Public, documented APIs only: the libobjc
 * runtime functions, {@code objc_msgSend} with per-signature downcall handles, and the
 * AppKit/WebKit/Foundation framework binaries (which resolve from the dyld shared cache
 * even though the files are not on disk).
 *
 * <p>ABI notes (arm64, macOS SDK): {@code CGFloat} is {@code double}; {@code NSInteger}/
 * {@code NSUInteger} are 64-bit; {@code BOOL} is one byte; there is no
 * {@code objc_msgSend_stret} — struct returns such as {@code NSRect} use the plain
 * {@code objc_msgSend} symbol with the struct layout in the {@link FunctionDescriptor}.
 */
final class ObjC {
    static final Linker LINKER = Linker.nativeLinker();

    /** Process-lifetime arena owning the dyld library lookups (never closed by design). */
    private static final Arena RUNTIME_ARENA = Arena.ofShared();

    private static final SymbolLookup OBJC =
            SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", RUNTIME_ARENA);
    @SuppressWarnings("unused") // loaded for its classes; symbols resolved via objc runtime
    private static final SymbolLookup FOUNDATION = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/Foundation.framework/Foundation", RUNTIME_ARENA);
    @SuppressWarnings("unused")
    private static final SymbolLookup APPKIT = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/AppKit.framework/AppKit", RUNTIME_ARENA);
    @SuppressWarnings("unused")
    private static final SymbolLookup WEBKIT = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/WebKit.framework/WebKit", RUNTIME_ARENA);
    static final SymbolLookup SYSTEM = LINKER.defaultLookup();

    // struct CGPoint { CGFloat x, y; } — CoreGraphics/CGGeometry.h, arm64 CGFloat=double.
    static final MemoryLayout NSPOINT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"), JAVA_DOUBLE.withName("y"));
    // struct CGSize { CGFloat width, height; }
    static final MemoryLayout NSSIZE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("width"), JAVA_DOUBLE.withName("height"));
    // struct CGRect { CGPoint origin; CGSize size; }
    static final MemoryLayout NSRECT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"), JAVA_DOUBLE.withName("y"),
            JAVA_DOUBLE.withName("width"), JAVA_DOUBLE.withName("height"));

    private static final MemorySegment MSG_SEND = OBJC.findOrThrow("objc_msgSend");

    private static final MethodHandle OBJC_GET_CLASS = LINKER.downcallHandle(
            OBJC.findOrThrow("objc_getClass"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle SEL_REGISTER_NAME = LINKER.downcallHandle(
            OBJC.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle ALLOCATE_CLASS_PAIR = LINKER.downcallHandle(
            OBJC.findOrThrow("objc_allocateClassPair"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle REGISTER_CLASS_PAIR = LINKER.downcallHandle(
            OBJC.findOrThrow("objc_registerClassPair"), FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle CLASS_ADD_METHOD = LINKER.downcallHandle(
            OBJC.findOrThrow("class_addMethod"),
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CLASS_ADD_PROTOCOL = LINKER.downcallHandle(
            OBJC.findOrThrow("class_addProtocol"),
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));
    private static final MethodHandle OBJC_GET_PROTOCOL = LINKER.downcallHandle(
            OBJC.findOrThrow("objc_getProtocol"), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle POOL_PUSH = LINKER.downcallHandle(
            OBJC.findOrThrow("objc_autoreleasePoolPush"), FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle POOL_POP = LINKER.downcallHandle(
            OBJC.findOrThrow("objc_autoreleasePoolPop"), FunctionDescriptor.ofVoid(ADDRESS));

    // Common message shapes; less common shapes go through msgSend(descriptor) directly.
    private static final FunctionDescriptor D_ID = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_ID_ID =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_ID_ID_ID =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_VOID = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_VOID_ID =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_VOID_ID_ID =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_VOID_BOOL =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_BOOL = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_BOOL_ID =
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS);

    private static final ConcurrentHashMap<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> MSG_SENDS =
            new ConcurrentHashMap<>();

    private ObjC() {
    }

    /** Per-signature {@code objc_msgSend} downcall handle (cached). */
    static MethodHandle msgSend(FunctionDescriptor descriptor) {
        return MSG_SENDS.computeIfAbsent(descriptor,
                d -> LINKER.downcallHandle(MSG_SEND, d));
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Objective-C call failed", t);
    }

    static MemorySegment cls(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try (Arena confined = Arena.ofConfined()) {
                MemorySegment result =
                        (MemorySegment) OBJC_GET_CLASS.invokeExact(confined.allocateFrom(n));
                if (result.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("Objective-C class not found: " + n);
                }
                return result;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        });
    }

    static MemorySegment sel(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena confined = Arena.ofConfined()) {
                return (MemorySegment) SEL_REGISTER_NAME.invokeExact(confined.allocateFrom(n));
            } catch (Throwable t) {
                throw rethrow(t);
            }
        });
    }

    // ---- common message sends ----

    static MemorySegment send(MemorySegment receiver, String selector) {
        try {
            return (MemorySegment) msgSend(D_ID).invokeExact(receiver, sel(selector));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment send(MemorySegment receiver, String selector, MemorySegment a) {
        try {
            return (MemorySegment) msgSend(D_ID_ID).invokeExact(receiver, sel(selector), a);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static MemorySegment send(MemorySegment receiver, String selector, MemorySegment a,
            MemorySegment b) {
        try {
            return (MemorySegment) msgSend(D_ID_ID_ID).invokeExact(receiver, sel(selector), a, b);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void sendVoid(MemorySegment receiver, String selector) {
        try {
            msgSend(D_VOID).invokeExact(receiver, sel(selector));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void sendVoid(MemorySegment receiver, String selector, MemorySegment a) {
        try {
            msgSend(D_VOID_ID).invokeExact(receiver, sel(selector), a);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void sendVoid(MemorySegment receiver, String selector, MemorySegment a,
            MemorySegment b) {
        try {
            msgSend(D_VOID_ID_ID).invokeExact(receiver, sel(selector), a, b);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void sendVoidBool(MemorySegment receiver, String selector, boolean value) {
        try {
            msgSend(D_VOID_BOOL).invokeExact(receiver, sel(selector), (byte) (value ? 1 : 0));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long sendLong(MemorySegment receiver, String selector) {
        try {
            return (long) msgSend(D_LONG).invokeExact(receiver, sel(selector));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean sendBool(MemorySegment receiver, String selector) {
        try {
            return (byte) msgSend(D_BOOL).invokeExact(receiver, sel(selector)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean sendBool(MemorySegment receiver, String selector, MemorySegment a) {
        try {
            return (byte) msgSend(D_BOOL_ID).invokeExact(receiver, sel(selector), a) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- strings ----

    /** Autoreleased NSString from a Java string ({@code stringWithUTF8String:}). */
    static MemorySegment nsString(String value) {
        try (Arena confined = Arena.ofConfined()) {
            return send(cls("NSString"), "stringWithUTF8String:", confined.allocateFrom(value));
        }
    }

    /**
     * Java string from an NSString via {@code UTF8String} (copied immediately: the
     * returned buffer only lives until the surrounding autorelease pool drains).
     */
    static String javaString(MemorySegment nsString) {
        if (nsString == null || nsString.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment utf8 = send(nsString, "UTF8String");
        if (utf8.equals(MemorySegment.NULL)) {
            return "";
        }
        return utf8.reinterpret(Long.MAX_VALUE).getString(0);
    }

    // ---- ownership (documented Cocoa memory rules: alloc/new/copy owned; balance once) ----

    static MemorySegment retain(MemorySegment object) {
        return send(object, "retain");
    }

    static void release(MemorySegment object) {
        if (object != null && !object.equals(MemorySegment.NULL)) {
            sendVoid(object, "release");
        }
    }

    /** Deferred release through the enclosing autorelease pool (documented ownership). */
    static void autorelease(MemorySegment object) {
        if (object != null && !object.equals(MemorySegment.NULL)) {
            send(object, "autorelease");
        }
    }

    static MemorySegment autoreleasePoolPush() {
        try {
            return (MemorySegment) POOL_PUSH.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void autoreleasePoolPop(MemorySegment pool) {
        try {
            POOL_POP.invokeExact(pool);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- dynamic classes (public libobjc APIs) ----

    static MemorySegment allocateClassPair(MemorySegment superClass, MemorySegment name) {
        try {
            return (MemorySegment) ALLOCATE_CLASS_PAIR.invokeExact(superClass, name, 0L);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void registerClassPair(MemorySegment cls) {
        try {
            REGISTER_CLASS_PAIR.invokeExact(cls);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean classAddMethod(MemorySegment cls, MemorySegment selector, MemorySegment imp,
            MemorySegment typeEncoding) {
        try {
            return (byte) CLASS_ADD_METHOD.invokeExact(cls, selector, imp, typeEncoding) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void classAddProtocolIfPresent(MemorySegment cls, String protocolName) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment protocol = (MemorySegment) OBJC_GET_PROTOCOL.invokeExact(
                    confined.allocateFrom(protocolName));
            if (!protocol.equals(MemorySegment.NULL)) {
                byte unusedResult = (byte) CLASS_ADD_PROTOCOL.invokeExact(cls, protocol);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
