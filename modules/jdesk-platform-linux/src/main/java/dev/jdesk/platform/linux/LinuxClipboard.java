package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Binary clipboard (GAP-004) over the GTK selection API with a custom target atom (the MIME
 * {@code type}). Reads are synchronous ({@code gtk_clipboard_wait_for_contents}); writes hand GTK
 * an owner get-func that serves the stored bytes when another app pastes, because GTK's clipboard
 * is ownership/callback based (there is no synchronous "set these bytes" call).
 *
 * <p>Honest status: compile-verified only — no Linux/GTK environment on the authoring machine. The
 * cross-process paste round-trip is runtime-verified on the Linux CI lane. As with any GTK
 * clipboard owner, written data lives only while this process owns the selection (a clipboard
 * manager may persist it after exit; without one it is lost) — a documented platform limit.
 */
final class LinuxClipboard {
    private static final Logger LOG = System.getLogger(LinuxClipboard.class.getName());

    /** Currently-owned selection payload, served from {@link #getFunc}. */
    private record Held(MemorySegment atom, MemorySegment data, int length) {
    }

    private static volatile Held current;
    /** Auto arena holding served byte copies; kept alive via {@link #current}. */
    private static final Arena DATA_ARENA = Arena.ofAuto();
    private static final MemorySegment GET_STUB;
    private static final MemorySegment CLEAR_STUB;

    static {
        try {
            GET_STUB = Gtk.upcall(MethodHandles.lookup().findStatic(LinuxClipboard.class, "getFunc",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                            int.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
            CLEAR_STUB = Gtk.upcall(MethodHandles.lookup().findStatic(LinuxClipboard.class,
                    "clearFunc", MethodType.methodType(void.class, MemorySegment.class,
                            MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private LinuxClipboard() {
    }

    /** Reads bytes stored under the MIME {@code type}; null when the clipboard has no such target. */
    static byte[] read(MemorySegment clipboard, String type) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = (MemorySegment) Gtk.GDK_ATOM_INTERN
                    .invokeExact(arena.allocateFrom(type), 0);
            MemorySegment selection = (MemorySegment) Gtk.GTK_CLIPBOARD_WAIT_FOR_CONTENTS
                    .invokeExact(clipboard, target);
            if (selection.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                int length = (int) Gtk.GTK_SELECTION_DATA_GET_LENGTH.invokeExact(selection);
                if (length <= 0) {
                    return new byte[0];
                }
                MemorySegment data = (MemorySegment) Gtk.GTK_SELECTION_DATA_GET_DATA
                        .invokeExact(selection);
                return data.reinterpret(length).toArray(JAVA_BYTE);
            } finally {
                Gtk.GTK_SELECTION_DATA_FREE.invokeExact(selection);
            }
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Binary clipboard read failed", null, t);
        }
    }

    /** Takes ownership of the clipboard, serving {@code data} under the MIME {@code type}. */
    static void write(MemorySegment clipboard, String type, byte[] data) {
        MemorySegment copy = DATA_ARENA.allocate(Math.max(1, data.length));
        MemorySegment.copy(data, 0, copy, JAVA_BYTE, 0, data.length);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment typeStr = arena.allocateFrom(type);
            MemorySegment atom = (MemorySegment) Gtk.GDK_ATOM_INTERN.invokeExact(typeStr, 0);
            current = new Held(atom, copy, data.length);

            MemorySegment targets = arena.allocate(16); // one GtkTargetEntry { char*; guint; guint }
            targets.set(ADDRESS, 0, typeStr); // gtk copies the target string into its target list
            targets.set(JAVA_INT, 8, 0);      // flags
            targets.set(JAVA_INT, 12, 0);     // info
            int ok = (int) Gtk.GTK_CLIPBOARD_SET_WITH_DATA.invokeExact(clipboard, targets, 1,
                    GET_STUB, CLEAR_STUB, MemorySegment.NULL);
            if (ok == 0) {
                current = null;
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "gtk_clipboard_set_with_data was refused");
            }
            Gtk.GTK_CLIPBOARD_STORE.invokeExact(clipboard); // ask a clipboard manager to persist
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Binary clipboard write failed", null, t);
        }
    }

    @SuppressWarnings("unused") // GtkClipboardGetFunc: serve the owned bytes on paste
    static void getFunc(MemorySegment clipboard, MemorySegment selectionData, int info,
            MemorySegment userData) {
        try {
            Held held = current;
            if (held != null) {
                Gtk.GTK_SELECTION_DATA_SET.invokeExact(selectionData, held.atom(), 8, held.data(),
                        held.length());
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Clipboard get-func failed", t);
        }
    }

    @SuppressWarnings("unused") // GtkClipboardClearFunc: another owner took the selection
    static void clearFunc(MemorySegment clipboard, MemorySegment userData) {
        current = null;
    }
}
