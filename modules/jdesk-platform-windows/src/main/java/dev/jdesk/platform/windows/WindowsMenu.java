package dev.jdesk.platform.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.jdesk.api.MenuItem;
import dev.jdesk.api.MenuSpec;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Windows context menus via {@code CreatePopupMenu}/{@code TrackPopupMenu}. {@code TrackPopupMenu}
 * with {@code TPM_RETURNCMD} is modal and returns the chosen command id, which maps back to the
 * action id. Compile-verified only; Windows CI lane runtime-verifies.
 */
final class WindowsMenu {
    private static final int MF_STRING = 0x0000;
    private static final int MF_GRAYED = 0x0001;
    private static final int MF_CHECKED = 0x0008;
    private static final int MF_POPUP = 0x0010;
    private static final int MF_SEPARATOR = 0x0800;
    private static final int TPM_RETURNCMD = 0x0100;
    private static final int TPM_RIGHTBUTTON = 0x0002;

    private static final MethodHandle CREATE_POPUP_MENU = WindowsDesktop.down(user32(),
            "CreatePopupMenu", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle APPEND_MENU = WindowsDesktop.down(user32(), "AppendMenuW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle TRACK_POPUP_MENU = WindowsDesktop.down(user32(),
            "TrackPopupMenu",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS));
    private static final MethodHandle DESTROY_MENU = WindowsDesktop.down(user32(), "DestroyMenu",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_CURSOR_POS = WindowsDesktop.down(user32(),
            "GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static java.lang.foreign.SymbolLookup user32() {
        return java.lang.foreign.SymbolLookup.libraryLookup("user32.dll", Arena.ofAuto());
    }

    private WindowsMenu() {
    }

    static Optional<String> showContextMenu(MemorySegment hwnd, MenuSpec spec) {
        Map<Integer, String> commands = new HashMap<>();
        try {
            MemorySegment menu = (MemorySegment) CREATE_POPUP_MENU.invokeExact();
            append(menu, spec.items(), commands, new int[] {1});
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment point = arena.allocate(8); // POINT { LONG x, y }
                int unusedPos = (int) GET_CURSOR_POS.invokeExact(point);
                int x = point.get(JAVA_INT, 0);
                int y = point.get(JAVA_INT, 4);
                int chosen = (int) TRACK_POPUP_MENU.invokeExact(menu,
                        TPM_RETURNCMD | TPM_RIGHTBUTTON, x, y, 0, hwnd, MemorySegment.NULL);
                return Optional.ofNullable(commands.get(chosen));
            } finally {
                int unusedDestroy = (int) DESTROY_MENU.invokeExact(menu);
            }
        } catch (Throwable t) {
            throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                    "Context menu failed", null, t);
        }
    }

    private static void append(MemorySegment menu, List<MenuItem> items,
            Map<Integer, String> commands, int[] nextId) throws Throwable {
        for (MenuItem item : items) {
            switch (item) {
                case MenuItem.Separator ignored -> {
                    int unused = (int) APPEND_MENU.invokeExact(menu, MF_SEPARATOR, 0L,
                            MemorySegment.NULL);
                }
                case MenuItem.Submenu submenu -> {
                    MemorySegment child = (MemorySegment) CREATE_POPUP_MENU.invokeExact();
                    append(child, submenu.items(), commands, nextId);
                    try (Arena arena = Arena.ofConfined()) {
                        int unused = (int) APPEND_MENU.invokeExact(menu, MF_STRING | MF_POPUP,
                                child.address(), WindowsDesktop.wide(arena, submenu.label()));
                    }
                }
                case MenuItem.Action action -> {
                    int id = nextId[0]++;
                    commands.put(id, action.id());
                    int flags = MF_STRING
                            | (action.checked() ? MF_CHECKED : 0)
                            | (action.enabled() ? 0 : MF_GRAYED);
                    try (Arena arena = Arena.ofConfined()) {
                        int unused = (int) APPEND_MENU.invokeExact(menu, flags, (long) id,
                                WindowsDesktop.wide(arena, action.label()));
                    }
                }
            }
        }
    }
}
