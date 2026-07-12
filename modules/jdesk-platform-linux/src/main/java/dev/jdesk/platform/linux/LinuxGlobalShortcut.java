package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OS-wide hotkeys on X11 via {@code XGrabKey} on the root window, with a GDK event filter
 * dispatching matching {@code KeyPress} events to the registered callback. Each hotkey is grabbed
 * four times (with and without the {@code CapsLock}/{@code NumLock} lock masks) so it fires
 * regardless of lock state, and the filter compares the event modifiers with the lock bits masked
 * out.
 *
 * <p>Honest status: compile-verified only — no Linux environment on the authoring machine; the
 * grab + key-press dispatch is runtime-verified on the X11 CI lane. <b>Wayland is not supported</b>:
 * Wayland has no global-grab protocol (only the very new {@code org.freedesktop.portal}
 * GlobalShortcuts portal), so under a Wayland session this throws a clear error rather than
 * silently doing nothing.
 */
final class LinuxGlobalShortcut {
    private static final Logger LOG = System.getLogger(LinuxGlobalShortcut.class.getName());

    // X11 modifier masks and event constants.
    private static final int SHIFT_MASK = 1 << 0;
    private static final int LOCK_MASK = 1 << 1;    // CapsLock
    private static final int CONTROL_MASK = 1 << 2;
    private static final int MOD1_MASK = 1 << 3;    // Alt
    private static final int MOD2_MASK = 1 << 4;    // NumLock
    private static final int MOD4_MASK = 1 << 6;    // Super / Win
    private static final int KEY_PRESS = 2;
    private static final int GRAB_MODE_ASYNC = 1;
    private static final int GDK_FILTER_CONTINUE = 0;
    /** Lock-key combinations grabbed alongside the base modifiers. */
    private static final int[] LOCK_COMBOS = {0, LOCK_MASK, MOD2_MASK, LOCK_MASK | MOD2_MASK};
    // XKeyEvent field offsets (x86-64): unsigned int state; unsigned int keycode.
    private static final int XKEY_STATE_OFFSET = 80;
    private static final int XKEY_KEYCODE_OFFSET = 84;

    private static final Arena ARENA = Arena.ofShared();
    private static final SymbolLookup GDK = SymbolLookup.libraryLookup("libgdk-3.so.0", ARENA);
    private static final SymbolLookup X11 = SymbolLookup.libraryLookup("libX11.so.6", ARENA);

    private static final MethodHandle GDK_X11_GET_DEFAULT_XDISPLAY = dl(GDK,
            "gdk_x11_get_default_xdisplay", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle GDK_X11_GET_DEFAULT_ROOT_XWINDOW = dl(GDK,
            "gdk_x11_get_default_root_xwindow", FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle GDK_WINDOW_ADD_FILTER = dl(GDK, "gdk_window_add_filter",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle X_STRING_TO_KEYSYM = dl(X11, "XStringToKeysym",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle X_KEYSYM_TO_KEYCODE = dl(X11, "XKeysymToKeycode",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_LONG));
    private static final MethodHandle X_GRAB_KEY = dl(X11, "XGrabKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT,
                    JAVA_INT, JAVA_INT));
    private static final MethodHandle X_UNGRAB_KEY = dl(X11, "XUngrabKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));

    private record Binding(int keycode, int modifiers, Runnable callback) {
    }

    private static final List<Binding> BINDINGS = new CopyOnWriteArrayList<>();
    private static final MemorySegment FILTER_STUB;
    private static volatile boolean filterInstalled;

    static {
        try {
            FILTER_STUB = Gtk.upcall(MethodHandles.lookup().findStatic(LinuxGlobalShortcut.class,
                    "eventFilter", MethodType.methodType(int.class, MemorySegment.class,
                            MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private LinuxGlobalShortcut() {
    }

    /** @return an idempotent unregister action. UI thread only. */
    static synchronized Runnable register(String accelerator, Runnable callback) {
        if (System.getenv("WAYLAND_DISPLAY") != null) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Global shortcuts require an X11 session; they are not supported under Wayland "
                            + "(no global-grab protocol). Accelerator: " + accelerator);
        }
        int modifiers = parseModifiers(accelerator);
        if (modifiers == 0) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "A global shortcut needs at least one modifier: " + accelerator);
        }
        long keysym = keysym(accelerator);
        try {
            MemorySegment display = (MemorySegment) GDK_X11_GET_DEFAULT_XDISPLAY.invokeExact();
            if (display.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "No X11 display; global shortcuts are unavailable in this session");
            }
            long root = (long) GDK_X11_GET_DEFAULT_ROOT_XWINDOW.invokeExact();
            int keycode = (byte) X_KEYSYM_TO_KEYCODE.invokeExact(display, keysym) & 0xFF;
            if (keycode == 0) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "No key code for accelerator: " + accelerator);
            }
            ensureFilterInstalled();
            for (int lock : LOCK_COMBOS) {
                int unused = (int) X_GRAB_KEY.invokeExact(display, keycode, modifiers | lock, root,
                        0, GRAB_MODE_ASYNC, GRAB_MODE_ASYNC);
            }
            Binding binding = new Binding(keycode, modifiers, callback);
            BINDINGS.add(binding);
            AtomicBoolean done = new AtomicBoolean();
            return () -> {
                if (done.compareAndSet(false, true)) {
                    BINDINGS.remove(binding);
                    try {
                        for (int lock : LOCK_COMBOS) {
                            int unused = (int) X_UNGRAB_KEY.invokeExact(display, keycode,
                                    modifiers | lock, root);
                        }
                    } catch (Throwable t) {
                        LOG.log(Level.DEBUG, "XUngrabKey failed", t);
                    }
                }
            };
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "XGrabKey failed", null, t);
        }
    }

    private static synchronized void ensureFilterInstalled() throws Throwable {
        if (filterInstalled) {
            return;
        }
        // NULL window => a filter for all native events on the default display.
        GDK_WINDOW_ADD_FILTER.invokeExact(MemorySegment.NULL, FILTER_STUB, MemorySegment.NULL);
        filterInstalled = true;
    }

    @SuppressWarnings("unused") // GdkFilterFunc for grabbed key presses
    static int eventFilter(MemorySegment xevent, MemorySegment gdkEvent, MemorySegment data) {
        try {
            MemorySegment event = xevent.reinterpret(96);
            if (event.get(JAVA_INT, 0) == KEY_PRESS) {
                int state = event.get(JAVA_INT, XKEY_STATE_OFFSET) & ~(LOCK_MASK | MOD2_MASK);
                int keycode = event.get(JAVA_INT, XKEY_KEYCODE_OFFSET);
                for (Binding binding : BINDINGS) {
                    if (binding.keycode() == keycode && binding.modifiers() == state) {
                        binding.callback().run(); // GDK dispatches on the UI thread
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Global shortcut filter failed", e);
        }
        return GDK_FILTER_CONTINUE; // never swallow the event
    }

    private static int parseModifiers(String accelerator) {
        String[] parts = accelerator.split("\\+");
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toLowerCase(Locale.ROOT)) {
                case "cmdorctrl", "ctrl", "control" -> modifiers |= CONTROL_MASK;
                case "cmd", "command", "meta", "win", "super" -> modifiers |= MOD4_MASK;
                case "alt", "option", "opt" -> modifiers |= MOD1_MASK;
                case "shift" -> modifiers |= SHIFT_MASK;
                default -> { }
            }
        }
        return modifiers;
    }

    /** Maps the accelerator's final token to an X11 keysym via {@code XStringToKeysym}. */
    private static long keysym(String accelerator) {
        String[] parts = accelerator.split("\\+");
        String key = parts[parts.length - 1].trim();
        String name = xKeysymName(key);
        try (Arena arena = Arena.ofConfined()) {
            long keysym = (long) X_STRING_TO_KEYSYM.invokeExact(arena.allocateFrom(name));
            if (keysym == 0) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "Unsupported global shortcut key: " + key);
            }
            return keysym;
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "XStringToKeysym failed", null, t);
        }
    }

    /** Translates a JDesk key token into the X11 keysym name expected by {@code XStringToKeysym}. */
    private static String xKeysymName(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        if (key.length() == 1) {
            char c = upper.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return String.valueOf(Character.toLowerCase(c)); // X keysym for letters is lowercase
            }
            if (c >= '0' && c <= '9') {
                return key;
            }
        }
        if (upper.length() >= 2 && upper.charAt(0) == 'F') {
            try {
                int n = Integer.parseInt(upper.substring(1));
                if (n >= 1 && n <= 24) {
                    return "F" + n;
                }
            } catch (NumberFormatException ignored) {
                // fall through to named keys
            }
        }
        return switch (upper) {
            case "SPACE" -> "space";
            case "RETURN", "ENTER" -> "Return";
            case "TAB" -> "Tab";
            case "ESCAPE", "ESC" -> "Escape";
            default -> throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Unsupported global shortcut key: " + key);
        };
    }

    private static MethodHandle dl(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return java.lang.foreign.Linker.nativeLinker()
                .downcallHandle(lookup.findOrThrow(name), descriptor);
    }
}
