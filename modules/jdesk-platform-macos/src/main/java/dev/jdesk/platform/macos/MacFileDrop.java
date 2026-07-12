package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * OS file drops onto the web view. {@code JDeskDropWebView} is a WKWebView subclass whose
 * {@code performDragOperation:} pulls the dropped absolute paths off the drag pasteboard,
 * hands them to the window's listener, and then calls {@code super} so in-page HTML5
 * drag-and-drop keeps working. The dragging methods also call {@code super}, so a page that
 * accepts HTML5 drops is unaffected.
 *
 * <p>Honest status: this is a best-effort implementation of the documented subclass pattern.
 * The subclass is created and the web view keeps rendering and doing IPC (verified). Whether
 * a real Finder drag reaches this override and yields the paths is a GUI gesture that cannot
 * be synthesized here and is not auto-tested.
 */
final class MacFileDrop {
    private static final Logger LOG = System.getLogger(MacFileDrop.class.getName());
    private static final long NS_DRAG_OPERATION_COPY = 1;
    private static final Arena CLASS_ARENA = Arena.ofShared();

    private static final Map<Long, Consumer<List<Path>>> LISTENERS = new ConcurrentHashMap<>();
    private static volatile MemorySegment dropWebViewClass;

    private MacFileDrop() {
    }

    /** The {@code JDeskDropWebView} class (subclass of WKWebView), created once. */
    static synchronized MemorySegment webViewClass() {
        if (dropWebViewClass != null) {
            return dropWebViewClass;
        }
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment cls = ObjC.allocateClassPair(ObjC.cls("WKWebView"),
                    confined.allocateFrom("JDeskDropWebView"));
            if (cls.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("allocateClassPair(WKWebView) failed");
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType op = MethodType.methodType(long.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class);
            MethodType bool = MethodType.methodType(byte.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class);
            FunctionDescriptor opDesc = FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);
            FunctionDescriptor boolDesc = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS);
            addMethod(cls, "draggingEntered:", "Q@:@", opDesc,
                    lookup.findStatic(MacFileDrop.class, "impDraggingEntered", op));
            addMethod(cls, "draggingUpdated:", "Q@:@", opDesc,
                    lookup.findStatic(MacFileDrop.class, "impDraggingUpdated", op));
            addMethod(cls, "prepareForDragOperation:", "B@:@", boolDesc,
                    lookup.findStatic(MacFileDrop.class, "impPrepareForDragOperation", bool));
            addMethod(cls, "performDragOperation:", "B@:@", boolDesc,
                    lookup.findStatic(MacFileDrop.class, "impPerformDragOperation", bool));
            ObjC.registerClassPair(cls);
            dropWebViewClass = cls;
            return cls;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registers {@code listener} for drops onto {@code view}; returns an unsubscribe action. */
    static Runnable register(MemorySegment view, Consumer<List<Path>> listener) {
        long key = view.address();
        LISTENERS.put(key, listener);
        // Ensure the view accepts file drags (on top of WKWebView's own registration).
        MemorySegment types = ObjC.send(ObjC.cls("NSArray"), "arrayWithObject:",
                ObjC.nsString("NSFilenamesPboardType"));
        ObjC.sendVoid(view, "registerForDraggedTypes:", types);
        return () -> LISTENERS.remove(key);
    }

    private static void addMethod(MemorySegment cls, String selector, String typeEncoding,
            FunctionDescriptor descriptor, MethodHandle implementation) {
        MemorySegment stub = ObjC.LINKER.upcallStub(implementation, descriptor, CLASS_ARENA);
        if (!ObjC.classAddMethod(cls, ObjC.sel(selector), stub,
                CLASS_ARENA.allocateFrom(typeEncoding))) {
            throw new IllegalStateException("class_addMethod failed for " + selector);
        }
    }

    @SuppressWarnings("unused")
    static long impDraggingEntered(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        long superOp = callSuperOperation(self, cmd, sender);
        if (superOp != 0) {
            return superOp; // the page is handling this HTML5 drag
        }
        return LISTENERS.containsKey(self.address()) ? NS_DRAG_OPERATION_COPY : 0;
    }

    @SuppressWarnings("unused")
    static long impDraggingUpdated(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        return impDraggingEntered(self, cmd, sender);
    }

    @SuppressWarnings("unused")
    static byte impPrepareForDragOperation(MemorySegment self, MemorySegment cmd,
            MemorySegment sender) {
        byte superReady = callSuperBool(self, cmd, sender);
        return superReady != 0 ? superReady : (byte) 1;
    }

    @SuppressWarnings("unused")
    static byte impPerformDragOperation(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try {
            Consumer<List<Path>> listener = LISTENERS.get(self.address());
            if (listener != null) {
                List<Path> paths = extractPaths(sender);
                if (!paths.isEmpty()) {
                    listener.accept(paths);
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "File-drop dispatch failed", t);
        }
        return callSuperBool(self, cmd, sender); // let the page handle the HTML5 drop too
    }

    private static List<Path> extractPaths(MemorySegment draggingInfo) {
        MemorySegment pasteboard = ObjC.send(draggingInfo, "draggingPasteboard");
        MemorySegment files = ObjC.send(pasteboard, "propertyListForType:",
                ObjC.nsString("NSFilenamesPboardType"));
        if (files == null || files.equals(MemorySegment.NULL)) {
            return List.of();
        }
        long count = ObjC.sendLong(files, "count");
        List<Path> paths = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            String path = ObjC.javaString(ObjC.sendIndexed(files, "objectAtIndex:", i));
            if (path != null && !path.isEmpty()) {
                paths.add(Path.of(path));
            }
        }
        return List.copyOf(paths);
    }

    private static long callSuperOperation(MemorySegment self, MemorySegment cmd,
            MemorySegment sender) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sup = superStruct(arena, self);
            return (long) ObjC.msgSendSuper(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS))
                    .invokeExact(sup, cmd, sender);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static byte callSuperBool(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sup = superStruct(arena, self);
            return (byte) ObjC.msgSendSuper(FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS))
                    .invokeExact(sup, cmd, sender);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static MemorySegment superStruct(Arena arena, MemorySegment self) {
        MemorySegment sup = arena.allocate(ObjC.OBJC_SUPER);
        sup.set(ADDRESS, 0, self);
        sup.set(ADDRESS, ADDRESS.byteSize(), ObjC.cls("WKWebView"));
        return sup;
    }
}
