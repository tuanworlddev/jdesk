package dev.jdesk.platform.windows;

import dev.jdesk.api.MenuItem;

/** Pure {@code AppendMenuW} flag mapping kept independent from Win32 initialization. */
final class WindowsMenuFlags {
    private static final int MF_STRING = 0x0000;
    private static final int MF_GRAYED = 0x0001;
    private static final int MF_CHECKED = 0x0008;

    private WindowsMenuFlags() {}

    static int forAction(MenuItem.Action action) {
        return MF_STRING
                | (action.checked() ? MF_CHECKED : 0)
                | (action.enabled() ? 0 : MF_GRAYED);
    }
}
