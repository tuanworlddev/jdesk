package dev.jdesk.platform.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Builds one Java-implemented Objective-C class through the public runtime APIs:
 * {@code objc_allocateClassPair} + {@code class_addMethod} (with FFM upcall stubs as
 * IMPs) + {@code objc_registerClassPair}. Classes are process-lifetime; their IMP stubs
 * and type encodings live in a process-lifetime arena (same pattern as the Windows
 * adapter's shared WndProc class arena), so a straggler Objective-C dispatch can never
 * reach freed stub memory. Per-instance validity is controlled by peer maps and the
 * owning registry's {@link dev.jdesk.ffm.CallbackGate}.
 */
final class ObjCClassBuilder {
    /** Process-lifetime arena pinning IMP upcall stubs and type-encoding strings. */
    private static final Arena CLASS_ARENA = Arena.ofShared();

    private final String name;
    private final MemorySegment cls;

    ObjCClassBuilder(String name) {
        this.name = name;
        try (Arena confined = Arena.ofConfined()) {
            this.cls = ObjC.allocateClassPair(ObjC.cls("NSObject"), confined.allocateFrom(name));
        }
        if (cls.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("objc_allocateClassPair failed for " + name);
        }
    }

    /**
     * Adds one method. {@code implementation} must be a bound handle exactly matching
     * {@code descriptor} (leading {@code self} and {@code _cmd} pointers included) and
     * must never throw across the FFM boundary.
     */
    ObjCClassBuilder method(String selector, String typeEncoding, FunctionDescriptor descriptor,
            MethodHandle implementation) {
        MemorySegment stub = ObjC.LINKER.upcallStub(implementation, descriptor, CLASS_ARENA);
        boolean added = ObjC.classAddMethod(cls, ObjC.sel(selector), stub,
                CLASS_ARENA.allocateFrom(typeEncoding));
        if (!added) {
            throw new IllegalStateException(
                    "class_addMethod failed for " + name + " " + selector);
        }
        return this;
    }

    /** Declares protocol conformance when the protocol is registered (best effort). */
    ObjCClassBuilder protocol(String protocolName) {
        ObjC.classAddProtocolIfPresent(cls, protocolName);
        return this;
    }

    MemorySegment register() {
        ObjC.registerClassPair(cls);
        return cls;
    }
}
