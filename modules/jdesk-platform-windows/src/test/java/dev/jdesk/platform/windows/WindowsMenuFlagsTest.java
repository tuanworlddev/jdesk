package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.MenuItem;
import org.junit.jupiter.api.Test;

/** Pure {@code AppendMenuW} flag tests; no Win32 classes are loaded. */
class WindowsMenuFlagsTest {

    @Test
    void plainActionHasNoStateFlags() {
        assertThat(WindowsMenuFlags.forAction(MenuItem.action("a", "A"))).isEqualTo(0x0);
    }

    @Test
    void checkedActionSetsMfChecked() {
        assertThat(WindowsMenuFlags.forAction(MenuItem.check("wrap", "Wrap", true))).isEqualTo(0x8);
    }

    @Test
    void disabledActionSetsMfGrayed() {
        assertThat(WindowsMenuFlags.forAction(MenuItem.action("a", "A").enabled(false))).isEqualTo(0x1);
    }

    @Test
    void checkedAndDisabledCombineBothFlags() {
        MenuItem.Action item = MenuItem.check("x", "X", true).enabled(false);
        assertThat(WindowsMenuFlags.forAction(item)).isEqualTo(0x9);
    }
}
