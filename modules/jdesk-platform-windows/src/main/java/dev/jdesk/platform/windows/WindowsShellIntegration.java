package dev.jdesk.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.MenuSpec;
import dev.jdesk.api.TraySpec;
import dev.jdesk.webview.spi.TrayControl;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Windows shell integration (GAP-004): global hotkeys ({@code RegisterHotKey}), the system tray
 * and balloon notifications ({@code Shell_NotifyIconW}). All three need an HWND to receive their
 * messages, so this owns a single hidden message-only window whose WndProc fans WM_HOTKEY and the
 * tray callback out to the registered Java handlers.
 *
 * <p>Honest status: compile-verified only — no Windows environment on the authoring machine. The
 * message routing (a hotkey press, a tray click) is a live OS input event and is runtime-verified
 * on the Windows native CI lane, not here. A custom PNG tray icon is not converted at runtime
 * (that needs GDI+); the tray falls back to the default application icon — a documented limit.
 */
final class WindowsShellIntegration {
    private static final Logger LOG = System.getLogger(WindowsShellIntegration.class.getName());
    private static final String MSG_CLASS = "JDeskShellMsgWindow";

    // Window messages.
    private static final int WM_NULL = 0x0000;
    private static final int WM_CONTEXTMENU = 0x007B;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_APP = 0x8000;
    private static final int TRAY_CALLBACK = WM_APP + 1;

    // RegisterHotKey modifiers.
    private static final int MOD_ALT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_SHIFT = 0x0004;
    private static final int MOD_WIN = 0x0008;
    private static final int MOD_NOREPEAT = 0x4000;

    // Shell_NotifyIconW messages / flags.
    private static final int NIM_ADD = 0;
    private static final int NIM_MODIFY = 1;
    private static final int NIM_DELETE = 2;
    private static final int NIF_MESSAGE = 0x01;
    private static final int NIF_ICON = 0x02;
    private static final int NIF_TIP = 0x04;
    private static final int NIF_INFO = 0x10;
    private static final int NIIF_INFO = 0x01;
    private static final int IDI_APPLICATION = 32512;
    private static final int NOTIFY_UID = 0x7FFF; // dedicated icon for app-level balloons

    // NOTIFYICONDATAW (x64, Vista+ layout). cbSize = full struct including hBalloonIcon.
    private static final long NOTIFYICONDATAW_SIZE = 976;
    private static final int OFF_CBSIZE = 0;
    private static final int OFF_HWND = 8;
    private static final int OFF_UID = 16;
    private static final int OFF_UFLAGS = 20;
    private static final int OFF_UCALLBACK = 24;
    private static final int OFF_HICON = 32;
    private static final int OFF_SZTIP = 40;         // WCHAR[128]
    private static final int OFF_SZINFO = 304;       // WCHAR[256]
    private static final int OFF_SZINFOTITLE = 820;  // WCHAR[64]
    private static final int OFF_DWINFOFLAGS = 948;

    private static final Arena ARENA = Arena.ofShared(); // process-lifetime; never closed
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", ARENA);
    private static final SymbolLookup SHELL32 = SymbolLookup.libraryLookup("shell32.dll", ARENA);

    private static final MethodHandle REGISTER_HOTKEY = WindowsDesktop.down(USER32, "RegisterHotKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle UNREGISTER_HOTKEY = WindowsDesktop.down(USER32,
            "UnregisterHotKey", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle LOAD_ICON = WindowsDesktop.down(USER32, "LoadIconW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_FOREGROUND_WINDOW = WindowsDesktop.down(USER32,
            "SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SHELL_NOTIFY_ICON = WindowsDesktop.down(SHELL32,
            "Shell_NotifyIconW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));

    private static final Map<Integer, Runnable> HOTKEYS = new ConcurrentHashMap<>();
    private static final Map<Integer, Tray> TRAYS = new ConcurrentHashMap<>();
    private static final AtomicInteger HOTKEY_ID = new AtomicInteger(1);
    private static final AtomicInteger TRAY_ID = new AtomicInteger(1);

    private static volatile MemorySegment msgHwnd;
    private static volatile MemorySegment defaultIcon;
    private static boolean notifyIconAdded;

    private WindowsShellIntegration() {
    }

    // ------------------------------------------------------------------ global hotkeys

    /** Registers an OS-wide hotkey; returns an idempotent unregister action. UI thread only. */
    static synchronized Runnable registerHotkey(String accelerator, Runnable callback) {
        int modifiers = parseModifiers(accelerator);
        if (modifiers == 0) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "A global shortcut needs at least one modifier: " + accelerator);
        }
        int vk = parseKey(accelerator);
        MemorySegment hwnd = ensureMessageWindow();
        int id = HOTKEY_ID.getAndIncrement();
        HOTKEYS.put(id, callback);
        try {
            int ok = (int) REGISTER_HOTKEY.invokeExact(hwnd, id, modifiers | MOD_NOREPEAT, vk);
            if (ok == 0) {
                HOTKEYS.remove(id);
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "RegisterHotKey failed (already held by another app?) for " + accelerator);
            }
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            HOTKEYS.remove(id);
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "RegisterHotKey failed", null, t);
        }
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                HOTKEYS.remove(id);
                try {
                    int unused = (int) UNREGISTER_HOTKEY.invokeExact(hwnd, id);
                } catch (Throwable t) {
                    LOG.log(Level.DEBUG, "UnregisterHotKey failed", t);
                }
            }
        };
    }

    // ------------------------------------------------------------------ tray

    /** Creates a tray item backed by {@code Shell_NotifyIconW}. UI thread only. */
    static synchronized TrayControl createTray(TraySpec spec, Consumer<String> onAction) {
        MemorySegment hwnd = ensureMessageWindow();
        int uid = TRAY_ID.getAndIncrement();
        Tray tray = new Tray(hwnd, uid, spec.menu(), spec.title(), onAction);
        TRAYS.put(uid, tray);
        tray.add();
        return tray;
    }

    // ------------------------------------------------------------------ notifications

    /** Posts a balloon notification through a dedicated tray icon. UI thread only. */
    static synchronized void notify(String title, String body) {
        MemorySegment hwnd = ensureMessageWindow();
        if (!notifyIconAdded) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment add = arena.allocate(NOTIFYICONDATAW_SIZE);
                header(add, hwnd, NOTIFY_UID, NIF_ICON);
                add.set(ADDRESS, OFF_HICON, defaultIcon());
                shellNotify(NIM_ADD, add); // best effort; balloon still works if this is a no-op
            }
            notifyIconAdded = true;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(NOTIFYICONDATAW_SIZE);
            header(data, hwnd, NOTIFY_UID, NIF_INFO);
            putWide(data, OFF_SZINFOTITLE, 64, title == null ? "" : title);
            putWide(data, OFF_SZINFO, 256, body == null ? "" : body);
            data.set(JAVA_INT, OFF_DWINFOFLAGS, NIIF_INFO);
            if (!shellNotify(NIM_MODIFY, data)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Shell_NotifyIcon balloon failed");
            }
        }
    }

    // ------------------------------------------------------------------ message window

    private static synchronized MemorySegment ensureMessageWindow() {
        MemorySegment existing = msgHwnd;
        if (existing != null) {
            return existing;
        }
        try {
            MethodHandle wndProc = MethodHandles.lookup().findStatic(WindowsShellIntegration.class,
                    "wndProc", MethodType.methodType(long.class, MemorySegment.class, int.class,
                            long.class, long.class));
            MemorySegment stub = Linker.nativeLinker().upcallStub(wndProc, Win32.WNDPROC_DESC, ARENA);

            MemorySegment wndClass = ARENA.allocate(Win32.WNDCLASSEXW);
            wndClass.set(JAVA_INT, 0, (int) Win32.WNDCLASSEXW.byteSize());
            wndClass.set(ADDRESS, 8, stub);                       // lpfnWndProc
            wndClass.set(ADDRESS, 24, Win32.getModuleHandle());   // hInstance
            wndClass.set(ADDRESS, 64, WideStrings.alloc(ARENA, MSG_CLASS)); // lpszClassName
            short atom = Win32.registerClassEx(wndClass);
            if (atom == 0) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "RegisterClassExW failed for " + MSG_CLASS);
            }
            MemorySegment hwnd;
            try (Arena confined = Arena.ofConfined()) {
                hwnd = Win32.createWindowEx(0,
                        WideStrings.alloc(confined, MSG_CLASS),
                        WideStrings.alloc(confined, ""),
                        0, 0, 0, 0, 0,
                        MemorySegment.ofAddress(-3L), // HWND_MESSAGE
                        MemorySegment.NULL, Win32.getModuleHandle(), MemorySegment.NULL);
            }
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "CreateWindowExW failed for the shell message window");
            }
            msgHwnd = hwnd;
            return hwnd;
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Could not create the shell message window", null, t);
        }
    }

    @SuppressWarnings("unused") // WndProc upcall for the shell message window
    static long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            switch (msg) {
                case WM_HOTKEY -> {
                    Runnable callback = HOTKEYS.get((int) wParam);
                    if (callback != null) {
                        callback.run(); // delivered on the UI thread's message pump
                    }
                    return 0;
                }
                case TRAY_CALLBACK -> {
                    int mouse = (int) lParam;
                    if (mouse == WM_RBUTTONUP || mouse == WM_CONTEXTMENU) {
                        Tray tray = TRAYS.get((int) wParam);
                        if (tray != null) {
                            tray.showMenu();
                        }
                    }
                    return 0;
                }
                default -> {
                    return Win32.defWindowProc(hwnd, msg, wParam, lParam);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Shell message dispatch failed msg=0x{0}",
                    Integer.toHexString(msg), e);
            return Win32.defWindowProc(hwnd, msg, wParam, lParam);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** One installed tray icon. */
    private static final class Tray implements TrayControl {
        private final MemorySegment hwnd;
        private final int uid;
        private final MenuSpec menu;
        private final Consumer<String> onAction;
        private volatile String title;
        private volatile boolean removed;

        Tray(MemorySegment hwnd, int uid, MenuSpec menu, String title, Consumer<String> onAction) {
            this.hwnd = hwnd;
            this.uid = uid;
            this.menu = menu;
            this.title = title;
            this.onAction = onAction;
        }

        void add() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocate(NOTIFYICONDATAW_SIZE);
                header(data, hwnd, uid, NIF_MESSAGE | NIF_ICON | NIF_TIP);
                data.set(JAVA_INT, OFF_UCALLBACK, TRAY_CALLBACK);
                data.set(ADDRESS, OFF_HICON, defaultIcon());
                putWide(data, OFF_SZTIP, 128, title);
                if (!shellNotify(NIM_ADD, data)) {
                    throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Shell_NotifyIcon add failed");
                }
            }
        }

        @Override
        public void setTitle(String value) {
            this.title = value;
            if (removed) {
                return;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocate(NOTIFYICONDATAW_SIZE);
                header(data, hwnd, uid, NIF_TIP);
                putWide(data, OFF_SZTIP, 128, value);
                shellNotify(NIM_MODIFY, data);
            }
        }

        @Override
        public void remove() {
            if (removed) {
                return;
            }
            removed = true;
            TRAYS.remove(uid);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocate(NOTIFYICONDATAW_SIZE);
                header(data, hwnd, uid, 0);
                shellNotify(NIM_DELETE, data);
            }
        }

        void showMenu() {
            // Classic tray requirement: foreground the owner so the menu tracks and dismisses.
            try {
                int unused = (int) SET_FOREGROUND_WINDOW.invokeExact(hwnd);
            } catch (Throwable t) {
                LOG.log(Level.DEBUG, "SetForegroundWindow failed", t);
            }
            Optional<String> chosen = WindowsMenu.showContextMenu(hwnd, menu);
            Win32.postMessage(hwnd, WM_NULL, 0, 0); // the documented dismiss fix
            chosen.ifPresent(onAction::accept);
        }
    }

    private static void header(MemorySegment data, MemorySegment hwnd, int uid, int flags) {
        data.set(JAVA_INT, OFF_CBSIZE, (int) NOTIFYICONDATAW_SIZE);
        data.set(ADDRESS, OFF_HWND, hwnd);
        data.set(JAVA_INT, OFF_UID, uid);
        data.set(JAVA_INT, OFF_UFLAGS, flags);
    }

    private static boolean shellNotify(int message, MemorySegment data) {
        try {
            return (int) SHELL_NOTIFY_ICON.invokeExact(message, data) != 0;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Shell_NotifyIcon failed", null, t);
        }
    }

    private static MemorySegment defaultIcon() {
        MemorySegment icon = defaultIcon;
        if (icon != null) {
            return icon;
        }
        try {
            icon = (MemorySegment) LOAD_ICON.invokeExact(MemorySegment.NULL,
                    MemorySegment.ofAddress(IDI_APPLICATION)); // MAKEINTRESOURCE(IDI_APPLICATION)
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "LoadIcon failed", null, t);
        }
        defaultIcon = icon;
        return icon;
    }

    /** Writes a NUL-terminated UTF-16LE string into a fixed WCHAR[maxChars] struct field. */
    private static void putWide(MemorySegment struct, int offset, int maxChars, String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        int maxBytes = (maxChars - 1) * 2;
        int n = Math.min(utf16.length, maxBytes);
        MemorySegment.copy(utf16, 0, struct, JAVA_BYTE, offset, n);
        struct.set(JAVA_SHORT, offset + n, (short) 0); // arena memory is already zeroed
    }

    private static int parseModifiers(String accelerator) {
        String[] parts = accelerator.split("\\+");
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toLowerCase(Locale.ROOT)) {
                case "cmdorctrl", "ctrl", "control" -> modifiers |= MOD_CONTROL;
                case "cmd", "command", "meta", "win", "super" -> modifiers |= MOD_WIN;
                case "alt", "option", "opt" -> modifiers |= MOD_ALT;
                case "shift" -> modifiers |= MOD_SHIFT;
                default -> { }
            }
        }
        return modifiers;
    }

    private static int parseKey(String accelerator) {
        String[] parts = accelerator.split("\\+");
        String key = parts[parts.length - 1].trim().toUpperCase(Locale.ROOT);
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return c; // VK_A..VK_Z == ASCII 'A'..'Z'
            }
            if (c >= '0' && c <= '9') {
                return c; // VK_0..VK_9 == ASCII '0'..'9'
            }
        }
        if (key.startsWith("F") && key.length() <= 3) {
            try {
                int n = Integer.parseInt(key.substring(1));
                if (n >= 1 && n <= 24) {
                    return 0x70 + (n - 1); // VK_F1..VK_F24
                }
            } catch (NumberFormatException ignored) {
                // fall through to named keys
            }
        }
        return switch (key) {
            case "SPACE" -> 0x20;
            case "RETURN", "ENTER" -> 0x0D;
            case "TAB" -> 0x09;
            case "ESCAPE", "ESC" -> 0x1B;
            default -> throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Unsupported global shortcut key: " + key);
        };
    }
}
