package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * OS file drops onto a GTK widget: {@code gtk_drag_dest_set} + URI targets, dispatching the
 * dropped absolute paths from the {@code drag-data-received} signal. Compile-verified only;
 * the GTK drag signals are runtime-verified on the Linux CI lane.
 */
final class LinuxFileDrop {
    private static final Logger LOG = System.getLogger(LinuxFileDrop.class.getName());
    private static final int GTK_DEST_DEFAULT_ALL = 0x07;
    private static final int GDK_ACTION_COPY = 1 << 1;

    private static final Map<Long, Consumer<List<Path>>> LISTENERS = new ConcurrentHashMap<>();
    private static final MemorySegment RECEIVED_STUB;

    static {
        try {
            RECEIVED_STUB = Gtk.upcall(MethodHandles.lookup().findStatic(LinuxFileDrop.class,
                    "onDragDataReceived", MethodType.methodType(void.class, MemorySegment.class,
                            MemorySegment.class, int.class, int.class, MemorySegment.class,
                            int.class, int.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                            JAVA_INT, JAVA_INT, ADDRESS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private LinuxFileDrop() {
    }

    static Runnable register(MemorySegment widget, Consumer<List<Path>> listener) {
        long key = widget.address();
        LISTENERS.put(key, listener);
        try {
            Gtk.GTK_DRAG_DEST_SET.invokeExact(widget, GTK_DEST_DEFAULT_ALL, MemorySegment.NULL, 0,
                    GDK_ACTION_COPY);
            Gtk.GTK_DRAG_DEST_ADD_URI_TARGETS.invokeExact(widget);
            Gtk.signalConnect(widget, "drag-data-received", RECEIVED_STUB);
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
        return () -> LISTENERS.remove(key);
    }

    @SuppressWarnings("unused") // GTK "drag-data-received" callback
    static void onDragDataReceived(MemorySegment widget, MemorySegment context, int x, int y,
            MemorySegment selectionData, int info, int time, MemorySegment userData) {
        try {
            Consumer<List<Path>> listener = LISTENERS.get(widget.address());
            if (listener == null) {
                return;
            }
            MemorySegment uris = (MemorySegment) Gtk.GTK_SELECTION_DATA_GET_URIS
                    .invokeExact(selectionData);
            if (uris.equals(MemorySegment.NULL)) {
                return;
            }
            List<Path> paths = new ArrayList<>();
            MemorySegment array = uris.reinterpret(Long.MAX_VALUE);
            for (long i = 0; ; i++) {
                MemorySegment element = array.getAtIndex(ADDRESS, i);
                if (element.equals(MemorySegment.NULL)) {
                    break;
                }
                String uri = Gtk.javaString(element);
                if (uri != null && uri.startsWith("file://")) {
                    try {
                        paths.add(Path.of(URI.create(uri)));
                    } catch (RuntimeException ignored) {
                        // skip malformed URI
                    }
                }
            }
            Gtk.G_STRFREEV.invokeExact(uris);
            if (!paths.isEmpty()) {
                listener.accept(List.copyOf(paths));
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "GTK file-drop dispatch failed", t);
        }
    }
}
