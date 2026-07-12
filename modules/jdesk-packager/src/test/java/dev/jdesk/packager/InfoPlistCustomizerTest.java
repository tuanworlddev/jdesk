package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InfoPlistCustomizerTest {

    private static final String PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>CFBundleName</key>
              <string>Forge</string>
            </dict>
            </plist>
            """;

    @Test
    void injectsUrlSchemesAndUsageDescriptions() {
        String out = InfoPlistCustomizer.customize(PLIST, "com.example.forge",
                List.of("jdesk-forge", "forge"),
                Map.of("NSDesktopFolderUsageDescription", "Open dropped files"));

        assertThat(out).contains("<key>CFBundleURLTypes</key>");
        assertThat(out).contains("<string>com.example.forge</string>");
        assertThat(out).contains("<string>jdesk-forge</string>").contains("<string>forge</string>");
        assertThat(out).contains("<key>NSDesktopFolderUsageDescription</key>")
                .contains("<string>Open dropped files</string>");
        // Original content preserved; still valid plist shape.
        assertThat(out).contains("<key>CFBundleName</key>").contains("</dict>\n</plist>");
    }

    @Test
    void isIdempotent() {
        String once = InfoPlistCustomizer.customize(PLIST, "id", List.of("scheme"),
                Map.of("NSFooUsageDescription", "why"));
        String twice = InfoPlistCustomizer.customize(once, "id", List.of("scheme"),
                Map.of("NSFooUsageDescription", "why"));
        assertThat(twice).isEqualTo(once);
        assertThat(count(twice, "<key>CFBundleURLTypes</key>")).isEqualTo(1);
        assertThat(count(twice, "<key>NSFooUsageDescription</key>")).isEqualTo(1);
    }

    @Test
    void escapesXmlSpecialCharacters() {
        String out = InfoPlistCustomizer.customize(PLIST, "a&b<c>",
                List.of("sch&eme"), Map.of("NSKey", "text & <tag>"));
        assertThat(out).contains("a&amp;b&lt;c&gt;");
        assertThat(out).contains("sch&amp;eme");
        assertThat(out).contains("text &amp; &lt;tag&gt;");
        assertThat(out).doesNotContain("a&b<c>");
    }

    @Test
    void emptySchemesAndNoNewKeysLeaveThePlistUnchanged() {
        assertThat(InfoPlistCustomizer.customize(PLIST, "id", List.of(), Map.of()))
                .isEqualTo(PLIST);
    }

    @Test
    void missingRootDictThrows() {
        assertThatThrownBy(() -> InfoPlistCustomizer.customize(
                "<plist></plist>", "id", List.of("s"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
