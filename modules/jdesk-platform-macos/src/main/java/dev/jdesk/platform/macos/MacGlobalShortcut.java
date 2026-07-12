package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OS-wide hotkeys through the public Carbon {@code RegisterEventHotKey} API (the only public
 * global-hotkey mechanism on macOS). A single application event handler dispatches presses to
 * the registered callback by hotkey id. Registration is structurally verifiable
 * ({@code RegisterEventHotKey} returns {@code noErr}); the actual key press is a global input
 * event and is not auto-tested.
 */
final class MacGlobalShortcut {
    private static final Logger LOG = System.getLogger(MacGlobalShortcut.class.getName());

    private static final int CMD = 0x100;
    private static final int SHIFT = 0x200;
    private static final int OPTION = 0x800;
    private static final int CONTROL = 0x1000;
    private static final int SIGNATURE = 0x4A44534B; // 'JDSK'
    private static final int EVENT_CLASS_KEYBOARD = 0x6B657962; // 'keyb'
    private static final int EVENT_HOTKEY_PRESSED = 5;
    private static final int PARAM_DIRECT_OBJECT = 0x2D2D2D2D; // '----'
    private static final int TYPE_EVENT_HOTKEY_ID = 0x686B6964; // 'hkid'

    private static final MemoryLayout HOTKEY_ID = MemoryLayout.structLayout(
            JAVA_INT.withName("signature"), JAVA_INT.withName("id"));
    private static final MemoryLayout EVENT_TYPE_SPEC = MemoryLayout.structLayout(
            JAVA_INT.withName("eventClass"), JAVA_INT.withName("eventKind"));

    private static final Arena ARENA = Arena.ofShared();
    private static final SymbolLookup CARBON = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/Carbon.framework/Carbon", ARENA);

    private static final MethodHandle REGISTER = down("RegisterEventHotKey",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, HOTKEY_ID, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle UNREGISTER = down("UnregisterEventHotKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_APP_TARGET = down("GetApplicationEventTarget",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle INSTALL_HANDLER = down("InstallEventHandler",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_EVENT_PARAM = down("GetEventParameter",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG,
                    ADDRESS, ADDRESS));

    private static final Map<Integer, Runnable> CALLBACKS = new ConcurrentHashMap<>();
    private static final AtomicInteger ID_SEQ = new AtomicInteger(1);
    private static final Map<String, Integer> KEY_CODES = keyCodes();
    private static volatile boolean handlerInstalled;

    private MacGlobalShortcut() {
    }

    /** @return an idempotent unregister action. */
    static synchronized Runnable register(String accelerator, Runnable callback) {
        int modifiers = carbonModifiers(accelerator);
        if (modifiers == 0) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "A global shortcut needs at least one modifier: " + accelerator);
        }
        int keyCode = keyCodeFor(accelerator);
        ensureHandlerInstalled();

        int id = ID_SEQ.getAndIncrement();
        CALLBACKS.put(id, callback);
        MemorySegment hotKeyRef;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hkid = arena.allocate(HOTKEY_ID);
            hkid.set(JAVA_INT, 0, SIGNATURE);
            hkid.set(JAVA_INT, 4, id);
            MemorySegment refSlot = arena.allocate(ADDRESS);
            MemorySegment target = (MemorySegment) GET_APP_TARGET.invokeExact();
            int rc = (int) REGISTER.invokeExact(keyCode, modifiers, hkid, target, 0, refSlot);
            if (rc != 0) {
                CALLBACKS.remove(id);
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "RegisterEventHotKey failed (OSStatus " + rc + ") for " + accelerator);
            }
            hotKeyRef = refSlot.get(ADDRESS, 0);
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            CALLBACKS.remove(id);
            throw ObjC.rethrow(t);
        }

        MemorySegment ref = hotKeyRef;
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                CALLBACKS.remove(id);
                try {
                    int unused = (int) UNREGISTER.invokeExact(ref);
                } catch (Throwable t) {
                    LOG.log(Level.DEBUG, "UnregisterEventHotKey failed", t);
                }
            }
        };
    }

    private static synchronized void ensureHandlerInstalled() {
        if (handlerInstalled) {
            return;
        }
        try {
            MethodHandle body = MethodHandles.lookup().findStatic(MacGlobalShortcut.class,
                    "impHotKeyHandler", MethodType.methodType(int.class, MemorySegment.class,
                            MemorySegment.class, MemorySegment.class));
            MemorySegment upp = ObjC.LINKER.upcallStub(body,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), ARENA);
            MemorySegment spec = ARENA.allocate(EVENT_TYPE_SPEC);
            spec.set(JAVA_INT, 0, EVENT_CLASS_KEYBOARD);
            spec.set(JAVA_INT, 4, EVENT_HOTKEY_PRESSED);
            MemorySegment target = (MemorySegment) GET_APP_TARGET.invokeExact();
            int rc = (int) INSTALL_HANDLER.invokeExact(target, upp, 1L, spec,
                    MemorySegment.NULL, MemorySegment.NULL);
            if (rc != 0) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "InstallEventHandler failed (OSStatus " + rc + ")");
            }
            handlerInstalled = true;
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }

    @SuppressWarnings("unused") // invoked from Carbon via the installed event handler UPP
    static int impHotKeyHandler(MemorySegment callRef, MemorySegment event, MemorySegment userData) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hkid = arena.allocate(HOTKEY_ID);
            int rc = (int) GET_EVENT_PARAM.invokeExact(event, PARAM_DIRECT_OBJECT,
                    TYPE_EVENT_HOTKEY_ID, MemorySegment.NULL, HOTKEY_ID.byteSize(),
                    MemorySegment.NULL, hkid);
            if (rc == 0) {
                Runnable callback = CALLBACKS.get(hkid.get(JAVA_INT, 4));
                if (callback != null) {
                    callback.run(); // Carbon delivers on the main run loop (UI thread)
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Global hotkey dispatch failed", t);
        }
        return 0; // noErr
    }

    private static int keyCodeFor(String accelerator) {
        String[] parts = accelerator.split("\\+");
        String key = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
        Integer code = KEY_CODES.get(key);
        if (code == null) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Unsupported global shortcut key: " + key);
        }
        return code;
    }

    private static int carbonModifiers(String accelerator) {
        String[] parts = accelerator.split("\\+");
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toLowerCase(Locale.ROOT)) {
                case "cmd", "command", "cmdorctrl", "meta" -> modifiers |= CMD;
                case "ctrl", "control" -> modifiers |= CONTROL;
                case "alt", "option", "opt" -> modifiers |= OPTION;
                case "shift" -> modifiers |= SHIFT;
                default -> { }
            }
        }
        return modifiers;
    }

    private static Map<String, Integer> keyCodes() {
        Map<String, Integer> m = new java.util.HashMap<>();
        int[] letters = {0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, 45, 31, 35, 12, 15, 1, 17,
                32, 9, 13, 7, 16, 6};
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int i = 0; i < alpha.length(); i++) {
            m.put(String.valueOf(alpha.charAt(i)), letters[i]);
        }
        int[] digits = {29, 18, 19, 20, 21, 23, 22, 26, 28, 25};
        for (int i = 0; i < 10; i++) {
            m.put(String.valueOf(i), digits[i]);
        }
        int[] fkeys = {122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111};
        for (int i = 0; i < fkeys.length; i++) {
            m.put("F" + (i + 1), fkeys[i]);
        }
        m.put("SPACE", 49);
        m.put("RETURN", 36);
        m.put("ENTER", 36);
        m.put("TAB", 48);
        m.put("ESCAPE", 53);
        m.put("ESC", 53);
        return Map.copyOf(m);
    }

    private static MethodHandle down(String name, FunctionDescriptor descriptor) {
        return ObjC.LINKER.downcallHandle(
                CARBON.find(name).orElseThrow(() -> new IllegalStateException("symbol not found: " + name)),
                descriptor);
    }
}
