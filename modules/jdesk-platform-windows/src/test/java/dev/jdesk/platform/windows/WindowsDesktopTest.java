package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.SystemTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Headless Windows desktop smoke tests (no UI thread required). */
@EnabledOnOs(OS.WINDOWS)
class WindowsDesktopTest {

    @Test
    void systemThemeReadsRegistryWithoutThrowing() {
        // Exercises the registry-read FFM path; result is DARK or LIGHT (LIGHT if the key is
        // absent). We only assert it resolves to a valid value without crashing.
        SystemTheme theme = WindowsDesktop.systemTheme();
        assertThat(theme).isIn(SystemTheme.DARK, SystemTheme.LIGHT);
    }
}
