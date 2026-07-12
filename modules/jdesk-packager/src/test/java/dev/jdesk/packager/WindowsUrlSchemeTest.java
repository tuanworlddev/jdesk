package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsUrlSchemeTest {

    @Test
    void registersUrlProtocolAndOpenCommandPerScheme() {
        String reg = WindowsUrlScheme.regScript(List.of("jdesk-forge", "forge"),
                "C:\\Program Files\\Forge\\forge.exe");

        assertThat(reg).startsWith("Windows Registry Editor Version 5.00\r\n");
        assertThat(reg).contains("[HKEY_CURRENT_USER\\Software\\Classes\\jdesk-forge]");
        assertThat(reg).contains("[HKEY_CURRENT_USER\\Software\\Classes\\forge]");
        assertThat(reg).contains("\"URL Protocol\"=\"\"");
        assertThat(reg).contains("@=\"URL:jdesk-forge Protocol\"");
        assertThat(reg).contains(
                "[HKEY_CURRENT_USER\\Software\\Classes\\jdesk-forge\\shell\\open\\command]");
    }

    @Test
    void escapesBackslashesAndQuotesInTheCommandValue() {
        String reg = WindowsUrlScheme.regScript(List.of("myapp"),
                "C:\\Program Files\\My App\\app.exe");
        // In a .reg value: each backslash doubled, the wrapping quotes escaped, %1 quoted.
        assertThat(reg).contains(
                "@=\"\\\"C:\\\\Program Files\\\\My App\\\\app.exe\\\" \\\"%1\\\"\"");
    }

    @Test
    void usesCrlfLineEndings() {
        String reg = WindowsUrlScheme.regScript(List.of("s"), "app.exe");
        assertThat(reg).contains("\r\n");
        assertThat(reg.lines()).anyMatch(l -> l.equals("\"URL Protocol\"=\"\""));
    }

    @Test
    void rejectsEmptyAndMalformedSchemes() {
        assertThatThrownBy(() -> WindowsUrlScheme.regScript(List.of(), "app.exe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsUrlScheme.regScript(List.of("bad scheme"), "app.exe"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
