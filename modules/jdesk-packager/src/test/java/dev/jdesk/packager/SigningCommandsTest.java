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
        assertThat(SigningCommands.macStapleValidate(Path.of("out/JDesk.dmg")))
                .containsExactly("xcrun", "stapler", "validate", "out/JDesk.dmg");
    }

    @Test
    void windowsSigntoolUsesSha256AndTimestamp() {
        assertThat(SigningCommands.windowsSigntool(Path.of("JDesk.exe"), "Acme Inc",
                "http://timestamp.acme.com"))
                .containsExactly("signtool", "sign", "/fd", "SHA256", "/tr",
                        "http://timestamp.acme.com", "/td", "SHA256", "/n", "Acme Inc", "JDesk.exe");
        assertThat(SigningCommands.windowsVerify(Path.of("JDesk.exe")))
                .containsExactly("signtool", "verify", "/pa", "/all", "/v", "JDesk.exe");
    }

    @Test
    void windowsSigntoolAcceptsASha1Thumbprint() {
        String thumbprint = "0123456789ABCDEF0123456789ABCDEF01234567";
        assertThat(SigningCommands.windowsSigntool(Path.of("JDesk.msi"), thumbprint,
                "https://timestamp.example"))
                .containsExactly("signtool", "sign", "/fd", "SHA256", "/tr",
                        "https://timestamp.example", "/td", "SHA256", "/sha1", thumbprint,
                        "JDesk.msi");
    }

    @Test
    void linuxGpgProducesADetachedArmoredSignature() {
        assertThat(SigningCommands.linuxGpgDetachSign(Path.of("jdesk.deb"), "0xKEY"))
                .containsExactly("gpg", "--yes", "--armor", "--detach-sign",
                        "--local-user", "0xKEY", "jdesk.deb");
        assertThat(SigningCommands.linuxGpgDetachSignHeadless(
                Path.of("jdesk.deb"), "0xKEY"))
                .containsExactly("gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
                        "--passphrase-fd", "0", "--armor", "--detach-sign", "--local-user",
                        "0xKEY", "jdesk.deb");
        assertThat(SigningCommands.linuxGpgVerify(Path.of("jdesk.deb")))
                .containsExactly("gpg", "--batch", "--verify", "jdesk.deb.asc", "jdesk.deb");
    }

    @Test
    void blankCredentialsAreRejected() {
        assertThatThrownBy(() -> SigningCommands.macCodesign(Path.of("a"), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningCommands.windowsSigntool(Path.of("a"), "cert", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
