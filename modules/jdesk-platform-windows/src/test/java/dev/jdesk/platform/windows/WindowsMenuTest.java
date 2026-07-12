package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.MenuItem;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic test for the {@code AppendMenuW} flag mapping (no Win32 calls, runs on every OS).
 * MF_STRING=0x0, MF_GRAYED=0x1, MF_CHECKED=0x8.
 */
class WindowsMenuTest {

    @Test
    void plainActionHasNoStateFlags() {
        assertThat(WindowsMenu.actionFlags(MenuItem.action("a", "A"))).isEqualTo(0x0);
    }

    @Test
    void checkedActionSetsMfChecked() {
        assertThat(WindowsMenu.actionFlags(MenuItem.check("wrap", "Wrap", true))).isEqualTo(0x8);
    }

    @Test
    void disabledActionSetsMfGrayed() {
        assertThat(WindowsMenu.actionFlags(MenuItem.action("a", "A").enabled(false))).isEqualTo(0x1);
    }

    @Test
    void checkedAndDisabledCombineBothFlags() {
        MenuItem.Action item = MenuItem.check("x", "X", true).enabled(false);
        assertThat(WindowsMenu.actionFlags(item)).isEqualTo(0x9); // MF_CHECKED | MF_GRAYED
    }
}
