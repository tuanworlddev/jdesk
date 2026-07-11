package dev.jdesk.platform.windows;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * COM vtable invocation. A COM interface pointer is a pointer to a pointer to an array
 * of function pointers; slot indexes come from the generated reference in
 * {@code docs/verification/webview2-vtables-1.0.2903.40.txt} (SDK 1.0.2903.40).
 */
final class ComRuntime {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Map<FunctionDescriptor, MethodHandle> HANDLE_CACHE =
            new ConcurrentHashMap<>();

    /** IUnknown slots (all COM interfaces). */
    static final int QUERY_INTERFACE = 0;
    static final int ADD_REF = 1;
    static final int RELEASE = 2;

    static final FunctionDescriptor QUERY_INTERFACE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    static final FunctionDescriptor ADDREF_RELEASE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    private ComRuntime() {
    }

    /** Loads the function pointer at {@code slot} of {@code comObject}'s vtable. */
    static MemorySegment functionAt(MemorySegment comObject, int slot) {
        MemorySegment vtable = comObject.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        return vtable.reinterpret((slot + 1L) * ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, (long) slot * ValueLayout.ADDRESS.byteSize());
    }

    /**
     * Invokes vtable slot {@code slot} on {@code comObject}. The descriptor must include
     * the leading {@code this} pointer. Returns the raw result (HRESULT for most slots).
     */
    static Object invoke(MemorySegment comObject, int slot, FunctionDescriptor descriptor,
            Object... args) {
        MethodHandle handle = HANDLE_CACHE.computeIfAbsent(descriptor, LINKER::downcallHandle);
        Object[] fullArgs = new Object[args.length + 2];
        fullArgs[0] = functionAt(comObject, slot);
        fullArgs[1] = comObject;
        System.arraycopy(args, 0, fullArgs, 2, args.length);
        try {
            return handle.invokeWithArguments(fullArgs);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("COM invocation failed at slot " + slot, t);
        }
    }

    /** Invokes a slot returning HRESULT and checks it. */
    static void invokeChecked(MemorySegment comObject, int slot, String operation,
            FunctionDescriptor descriptor, Object... args) {
        int hr = (int) invoke(comObject, slot, descriptor, args);
        Hresult.check(hr, operation);
    }

    static int addRef(MemorySegment comObject) {
        return (int) invoke(comObject, ADD_REF, ADDREF_RELEASE_DESC);
    }

    static int release(MemorySegment comObject) {
        if (comObject == null || comObject.equals(MemorySegment.NULL)) {
            return 0;
        }
        return (int) invoke(comObject, RELEASE, ADDREF_RELEASE_DESC);
    }
}
