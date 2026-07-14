package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SigningCommandsTest {

    @Test
    void macCodesignAppliesHardenedRuntimeAndTimestamp() {
        assertThat(SigningCommands.macCodesign(Path.of("JDesk.app"), "Developer ID Application: Acme"))
                .containsExactly("codesign", "--force", "--options", "runtime", "--timestamp",
                        "--sign", "Developer ID Application: Acme", "JDesk.app");
    }

    @Test
    void macNotarizeAndStapleUseXcrun() {
        assertThat(SigningCommands.macNotarize(Path.of("out/JDesk.dmg"), "acme-profile"))
                .containsExactly("xcrun", "notarytool", "submit", "out/JDesk.dmg",
                        "--keychain-profile", "acme-profile", "--wait");
        assertThat(SigningCommands.macStaple(Path.of("out/JDesk.dmg")))
                .containsExactly("xcrun", "stapler", "staple", "out/JDesk.dmg");
    }

    @Test
    void windowsSigntoolUsesSha256AndTimestamp() {
        assertThat(SigningCommands.windowsSigntool(Path.of("JDesk.exe"), "Acme Inc",
                "http://timestamp.acme.com"))
                .containsExactly("signtool", "sign", "/fd", "SHA256", "/tr",
                        "http://timestamp.acme.com", "/td", "SHA256", "/n", "Acme Inc", "JDesk.exe");
    }

    @Test
    void linuxGpgProducesADetachedArmoredSignature() {
        assertThat(SigningCommands.linuxGpgDetachSign(Path.of("jdesk.deb"), "0xKEY"))
                .containsExactly("gpg", "--batch", "--yes", "--armor", "--detach-sign",
                        "--local-user", "0xKEY", "jdesk.deb");
    }

    @Test
    void blankCredentialsAreRejected() {
        assertThatThrownBy(() -> SigningCommands.macCodesign(Path.of("a"), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningCommands.windowsSigntool(Path.of("a"), "cert", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
