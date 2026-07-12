package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxDesktopEntryTest {

    @Test
    void registersOneSchemeHandlerPerSchemeAndPassesTheUrl() {
        String entry = LinuxDesktopEntry.build("Forge", "/opt/forge/bin/forge",
                List.of("jdesk-forge", "forge"));

        assertThat(entry).startsWith("[Desktop Entry]\n");
        assertThat(entry).contains("Name=Forge");
        assertThat(entry).contains("Type=Application");
        // %u passes the activated URL as an argument to the launcher.
        assertThat(entry).contains("Exec=/opt/forge/bin/forge %u");
        assertThat(entry).contains(
                "MimeType=x-scheme-handler/jdesk-forge;x-scheme-handler/forge;");
    }

    @Test
    void quotesTheExecPathWhenItContainsSpaces() {
        String entry = LinuxDesktopEntry.build("My App", "/opt/My App/bin/app", List.of("myapp"));
        assertThat(entry).contains("Exec=\"/opt/My App/bin/app\" %u");
    }

    @Test
    void newlinesInValuesCannotCorruptTheFile() {
        String entry = LinuxDesktopEntry.build("Evil\nName", "/opt/app/bin/app", List.of("s"));
        assertThat(entry).doesNotContain("Evil\nName");
        assertThat(entry.lines().filter(l -> l.startsWith("Name=")).count()).isEqualTo(1);
    }

    @Test
    void rejectsEmptyAndMalformedSchemes() {
        assertThatThrownBy(() -> LinuxDesktopEntry.build("A", "/x", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LinuxDesktopEntry.build("A", "/x", List.of("bad scheme")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LinuxDesktopEntry.build("A", "/x", List.of("1nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
