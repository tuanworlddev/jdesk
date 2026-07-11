package dev.jdesk.platform.windows;

import dev.jdesk.ffm.CallbackGate;
import dev.jdesk.ffm.NativeCallbackRegistry;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java-implemented COM objects for WebView2 callbacks. The object layout is one vtable
 * pointer; the vtable holds upcall stubs pinned by the owning
 * {@link NativeCallbackRegistry} (spec section 6.2). IUnknown is implemented here:
 * QueryInterface answers the declared IIDs plus IUnknown; ref counting is Java-side and
 * the memory is arena-owned, so a stray Release can never free it early — the registry's
 * close (after gate quiescence) is the single point of release.
 */
final class ComCallback {
    private static final Logger LOG = System.getLogger(ComCallback.class.getName());
    private static final Linker LINKER = Linker.nativeLinker();
    private static final String IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046";

    /** One custom vtable slot: descriptor plus the bound implementation handle. */
    record Slot(FunctionDescriptor descriptor, MethodHandle implementation) {
    }

    private ComCallback() {
    }

    /**
     * Builds a COM object whose vtable is {@code [QI, AddRef, Release, slots...]}.
     * All slot handles must already be bound to their receivers and take the leading
     * {@code this} pointer.
     */
    static MemorySegment create(NativeCallbackRegistry registry, String name,
            List<String> iids, List<Slot> slots) {
        return createWithTearOffs(registry, name, iids, java.util.Map.of(), slots);
    }

    /**
     * Like {@link #create} but QueryInterface for an IID in {@code tearOffs} returns the
     * mapped object instead of {@code this} — required when interface vtable layouts
     * are incompatible (e.g. EnvironmentOptions vs EnvironmentOptions4).
     */
    static MemorySegment createWithTearOffs(NativeCallbackRegistry registry, String name,
            List<String> iids, java.util.Map<String, MemorySegment> tearOffs, List<Slot> slots) {
        Arena arena = registry.arena();
        CallbackGate gate = registry.gate();
        AtomicInteger refCount = new AtomicInteger(1);

        MemorySegment vtable = arena.allocate((3L + slots.size()) * ValueLayout.ADDRESS.byteSize());
        MemorySegment object = arena.allocate(ValueLayout.ADDRESS.byteSize());
        object.set(ValueLayout.ADDRESS, 0, vtable);

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            QueryInterfaceFn qi = (self, riid, ppv) -> {
                if (ppv.equals(MemorySegment.NULL)) {
                    return Hresult.E_FAIL;
                }
                MemorySegment out = ppv.reinterpret(ValueLayout.ADDRESS.byteSize());
                boolean supported = Guids.equals(riid, IID_IUNKNOWN)
                        || iids.stream().anyMatch(iid -> Guids.equals(riid, iid));
                if (supported) {
                    out.set(ValueLayout.ADDRESS, 0, self);
                    refCount.incrementAndGet();
                    return Hresult.S_OK;
                }
                for (var tearOff : tearOffs.entrySet()) {
                    if (Guids.equals(riid, tearOff.getKey())) {
                        out.set(ValueLayout.ADDRESS, 0, tearOff.getValue());
                        ComRuntime.addRef(tearOff.getValue());
                        return Hresult.S_OK;
                    }
                }
                out.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                return Hresult.E_NOINTERFACE;
            };
            MethodHandle qiHandle = lookup.findVirtual(QueryInterfaceFn.class, "invoke",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class)).bindTo(qi);
            MemorySegment qiStub = LINKER.upcallStub(qiHandle,
                    ComRuntime.QUERY_INTERFACE_DESC, arena);

            RefFn addRef = self -> refCount.incrementAndGet();
            RefFn release = self -> {
                int remaining = refCount.decrementAndGet();
                if (remaining < 0) {
                    LOG.log(Level.WARNING, "COM object {0} over-released", name);
                }
                return Math.max(remaining, 0);
            };
            MethodType refType = MethodType.methodType(int.class, MemorySegment.class);
            MemorySegment addRefStub = LINKER.upcallStub(
                    lookup.findVirtual(RefFn.class, "invoke", refType).bindTo(addRef),
                    ComRuntime.ADDREF_RELEASE_DESC, arena);
            MemorySegment releaseStub = LINKER.upcallStub(
                    lookup.findVirtual(RefFn.class, "invoke", refType).bindTo(release),
                    ComRuntime.ADDREF_RELEASE_DESC, arena);

            vtable.set(ValueLayout.ADDRESS, 0, qiStub);
            vtable.set(ValueLayout.ADDRESS, ValueLayout.ADDRESS.byteSize(), addRefStub);
            vtable.set(ValueLayout.ADDRESS, 2 * ValueLayout.ADDRESS.byteSize(), releaseStub);

            int index = 3;
            for (Slot slot : slots) {
                MemorySegment stub = LINKER.upcallStub(
                        gated(gate, name, slot), slot.descriptor(), arena);
                vtable.set(ValueLayout.ADDRESS, (long) index * ValueLayout.ADDRESS.byteSize(), stub);
                index++;
            }

            registry.register(new NativeCallbackRegistry.Registration(
                    name, refCount, qiHandle, object, null, () -> { }));
            return object;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to build COM callback " + name, e);
        }
    }

    /**
     * Wraps a slot implementation with the callback gate: a call arriving after owner
     * closure returns E_FAIL safely instead of touching freed state.
     */
    private static MethodHandle gated(CallbackGate gate, String name, Slot slot) {
        MethodType type = slot.implementation().type();
        GateChecker checker = new GateChecker(gate, name, slot.implementation());
        try {
            MethodHandle generic = MethodHandles.lookup().findVirtual(GateChecker.class,
                    "invoke", MethodType.methodType(int.class, Object[].class))
                    .bindTo(checker);
            return generic.asCollector(Object[].class, type.parameterCount()).asType(type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reflective gate wrapper; COM callbacks are not hot paths in this adapter. */
    static final class GateChecker {
        private final CallbackGate gate;
        private final String name;
        private final MethodHandle target;

        GateChecker(CallbackGate gate, String name, MethodHandle target) {
            this.gate = gate;
            this.name = name;
            this.target = target;
        }

        @SuppressWarnings("unused") // invoked via MethodHandle
        int invoke(Object[] args) {
            if (!gate.enter()) {
                LOG.log(Level.WARNING, "Rejected late COM callback {0} after close", name);
                return Hresult.E_FAIL;
            }
            try {
                return (int) target.invokeWithArguments(args);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "COM callback {0} threw", name, t);
                return Hresult.E_FAIL;
            } finally {
                gate.exit();
            }
        }
    }

    interface QueryInterfaceFn {
        int invoke(MemorySegment self, MemorySegment riid, MemorySegment ppv);
    }

    interface RefFn {
        int invoke(MemorySegment self);
    }

    // ---- common handler shapes ----

    interface HrPtrFn {
        int invoke(MemorySegment self, int hresult, MemorySegment pointer);
    }

    interface PtrPtrFn {
        int invoke(MemorySegment self, MemorySegment sender, MemorySegment args);
    }

    interface HrFn {
        int invoke(MemorySegment self, int hresult);
    }

    /** Handler with Invoke(HRESULT, T*) — completion handlers. */
    static MemorySegment hrPtrHandler(NativeCallbackRegistry registry, String name,
            String iid, HrPtrFn fn) {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(HrPtrFn.class, "invoke",
                    MethodType.methodType(int.class, MemorySegment.class, int.class,
                            MemorySegment.class)).bindTo(fn);
            return create(registry, name, List.of(iid), List.of(new Slot(
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS), handle)));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Handler with Invoke(sender*, args*) — event handlers. */
    static MemorySegment ptrPtrHandler(NativeCallbackRegistry registry, String name,
            String iid, PtrPtrFn fn) {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(PtrPtrFn.class, "invoke",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class)).bindTo(fn);
            return create(registry, name, List.of(iid), List.of(new Slot(
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), handle)));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Handler with Invoke(HRESULT) — capture completion. */
    static MemorySegment hrHandler(NativeCallbackRegistry registry, String name,
            String iid, HrFn fn) {
        try {
            MethodHandle handle = MethodHandles.lookup().findVirtual(HrFn.class, "invoke",
                    MethodType.methodType(int.class, MemorySegment.class, int.class)).bindTo(fn);
            return create(registry, name, List.of(iid), List.of(new Slot(
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT), handle)));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
