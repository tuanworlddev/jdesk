package dev.jdesk.platform.macos;

import dev.jdesk.ffm.CallbackGate;
import dev.jdesk.ffm.NativeCallbackRegistry;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Objective-C blocks built and invoked from Java. The block memory layout is the
 * publicly documented blocks ABI (clang "Block Implementation Specification",
 * Block-ABI-Apple): a literal {@code { isa, flags, reserved, invoke, descriptor }} with
 * the invoke pointer at offset 16 on arm64. Blocks created here use the exported
 * {@code _NSConcreteGlobalBlock} isa with {@code BLOCK_IS_GLOBAL}: a global block is
 * immortal to the runtime ({@code Block_copy} returns it unchanged), so WebKit may
 * retain the completion handler safely while the backing memory is pinned by the owning
 * {@link NativeCallbackRegistry} arena. No private API is involved.
 */
final class ObjCBlock {
    private static final Logger LOG = System.getLogger(ObjCBlock.class.getName());

    private static final MemorySegment GLOBAL_BLOCK_ISA =
            ObjC.SYSTEM.findOrThrow("_NSConcreteGlobalBlock");
    private static final int BLOCK_IS_GLOBAL = 1 << 28;
    private static final long BLOCK_LITERAL_SIZE = 32; // isa 8 + flags 4 + reserved 4 + invoke 8 + descriptor 8

    private static final FunctionDescriptor INVOKE2_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor INVOKE1_DESC =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor INVOKE0_DESC = FunctionDescriptor.ofVoid(ADDRESS);
    private static final MethodHandle FN0_INVOKE;
    private static final MethodHandle FN1_INVOKE;
    private static final MethodHandle FN2_INVOKE;
    /** Calls a received block's invoke pointer with one NSInteger argument. */
    private static final MethodHandle INVOKE_WITH_LONG = ObjC.LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));

    static {
        try {
            FN0_INVOKE = MethodHandles.lookup().findVirtual(Fn0.class, "invoke",
                    MethodType.methodType(void.class, MemorySegment.class));
            FN1_INVOKE = MethodHandles.lookup().findVirtual(Fn1.class, "invoke",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
            FN2_INVOKE = MethodHandles.lookup().findVirtual(Fn2.class, "invoke",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Java body of a no-argument completion block. */
    interface Fn0 {
        void invoke(MemorySegment block);
    }

    /** Java body of a one-object-argument completion block. */
    interface Fn1 {
        void invoke(MemorySegment block, MemorySegment arg0);
    }

    /** Java body of a two-object-argument completion block. */
    interface Fn2 {
        void invoke(MemorySegment block, MemorySegment arg0, MemorySegment arg1);
    }

    private ObjCBlock() {
    }

    /** Builds a completion block of shape {@code void (^)(void)}. */
    static MemorySegment create0(NativeCallbackRegistry registry, String name, Fn0 body) {
        Arena arena = registry.arena();
        CallbackGate gate = registry.gate();
        Fn0 gated = block -> {
            if (!gate.enter()) {
                LOG.log(Level.WARNING, "Rejected late block callback {0} after close", name);
                return;
            }
            try {
                body.invoke(block);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "Block callback {0} threw", name, t);
            } finally {
                gate.exit();
            }
        };
        MethodHandle handle = FN0_INVOKE.bindTo(gated);
        MemorySegment stub = ObjC.LINKER.upcallStub(handle, INVOKE0_DESC, arena);
        MemorySegment block = globalBlock(arena, stub);
        registry.register(new NativeCallbackRegistry.Registration(
                name, gated, handle, stub, null, () -> { }));
        return block;
    }

    /** Builds a completion block of shape {@code void (^)(id)}. */
    static MemorySegment create1(NativeCallbackRegistry registry, String name, Fn1 body) {
        Arena arena = registry.arena();
        CallbackGate gate = registry.gate();
        Fn1 gated = (block, arg0) -> {
            if (!gate.enter()) {
                LOG.log(Level.WARNING, "Rejected late block callback {0} after close", name);
                return;
            }
            try {
                body.invoke(block, arg0);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "Block callback {0} threw", name, t);
            } finally {
                gate.exit();
            }
        };
        MethodHandle handle = FN1_INVOKE.bindTo(gated);
        MemorySegment stub = ObjC.LINKER.upcallStub(handle, INVOKE1_DESC, arena);
        MemorySegment block = globalBlock(arena, stub);
        registry.register(new NativeCallbackRegistry.Registration(
                name, gated, handle, stub, null, () -> { }));
        return block;
    }

    /**
     * Builds a block of shape {@code void (^)(id, id)} — the shape of both
     * {@code evaluateJavaScript:} and {@code takeSnapshotWithConfiguration:} completion
     * handlers. The body runs through the registry gate and never throws across FFM.
     */
    static MemorySegment create2(NativeCallbackRegistry registry, String name, Fn2 body) {
        Arena arena = registry.arena();
        CallbackGate gate = registry.gate();
        Fn2 gated = (block, a, b) -> {
            if (!gate.enter()) {
                LOG.log(Level.WARNING, "Rejected late block callback {0} after close", name);
                return;
            }
            try {
                body.invoke(block, a, b);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "Block callback {0} threw", name, t);
            } finally {
                gate.exit();
            }
        };
        MethodHandle handle = FN2_INVOKE.bindTo(gated);
        MemorySegment stub = ObjC.LINKER.upcallStub(handle, INVOKE2_DESC, arena);

        MemorySegment block = globalBlock(arena, stub);

        registry.register(new NativeCallbackRegistry.Registration(
                name, gated, handle, stub, null, () -> { }));
        return block;
    }

    private static MemorySegment globalBlock(Arena arena, MemorySegment stub) {
        // struct Block_descriptor_1 { unsigned long reserved; unsigned long size; }
        MemorySegment descriptor = arena.allocate(16, 8);
        descriptor.set(JAVA_LONG, 0, 0L);
        descriptor.set(JAVA_LONG, 8, BLOCK_LITERAL_SIZE);

        MemorySegment block = arena.allocate(BLOCK_LITERAL_SIZE, 8);
        block.set(ADDRESS, 0, GLOBAL_BLOCK_ISA);
        block.set(JAVA_INT, 8, BLOCK_IS_GLOBAL);
        block.set(JAVA_INT, 12, 0);
        block.set(ADDRESS, 16, stub);
        block.set(ADDRESS, 24, descriptor);

        return block;
    }

    /**
     * Invokes a block received from the OS that takes one {@code NSInteger} (e.g. a
     * {@code WKNavigationActionPolicy} decision handler). Per the public blocks ABI the
     * invoke pointer sits at offset 16 and receives the block as its first argument.
     */
    static void invokeWithLong(MemorySegment block, long value) {
        MemorySegment invoke = block.reinterpret(BLOCK_LITERAL_SIZE).get(ADDRESS, 16);
        try {
            INVOKE_WITH_LONG.invokeExact(invoke, block, value);
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }
}
