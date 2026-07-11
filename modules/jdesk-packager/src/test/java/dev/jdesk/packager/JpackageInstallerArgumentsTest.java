package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JpackageInstallerArgumentsTest {

    @Test
    void buildsDmgArguments() {
        var args = JpackageInstallerArguments.builder()
                .type(JpackageInstallerArguments.Type.DMG)
                .name("Example")
                .appImage(Path.of("build/jdesk/package/Example.app"))
                .destination(Path.of("build/jdesk/installer"))
                .appVersion("1.2.3")
                .build()
                .toArguments();
        assertThat(args).containsExactly(
                "--type", "dmg",
                "--name", "Example",
                "--app-image", "build/jdesk/package/Example.app",
                "--dest", "build/jdesk/installer",
                "--app-version", "1.2.3");
    }

    @Test
    void macSigningIdentityAddsSignFlags() {
        var args = JpackageInstallerArguments.builder()
                .type(JpackageInstallerArguments.Type.PKG)
                .name("Example")
                .appImage(Path.of("Example.app"))
                .destination(Path.of("out"))
                .appVersion("1.0.0")
                .macSigningIdentity("Developer ID Installer: Example")
                .build()
                .toArguments();
        assertThat(args).contains("--mac-sign", "--mac-signing-key-user-name",
                "Developer ID Installer: Example");
    }

    @Test
    void windowsInstallerHasNoMacSignFlags() {
        var args = JpackageInstallerArguments.builder()
                .type(JpackageInstallerArguments.Type.MSI)
                .name("Example")
                .appImage(Path.of("Example"))
                .destination(Path.of("out"))
                .appVersion("1.0.0")
                .macSigningIdentity("ignored on windows")
                .build()
                .toArguments();
        assertThat(args).doesNotContain("--mac-sign");
        assertThat(args).startsWith("--type", "msi");
    }

    @Test
    void defaultTypePerOs() {
        assertThat(JpackageInstallerArguments.defaultForOs("Mac OS X"))
                .contains(JpackageInstallerArguments.Type.DMG);
        assertThat(JpackageInstallerArguments.defaultForOs("Windows Server 2025"))
                .contains(JpackageInstallerArguments.Type.MSI);
        assertThat(JpackageInstallerArguments.defaultForOs("Linux"))
                .contains(JpackageInstallerArguments.Type.DEB);
        assertThat(JpackageInstallerArguments.defaultForOs("Solaris")).isEmpty();
    }

    @Test
    void typeTargetsCorrectOs() {
        assertThat(JpackageInstallerArguments.Type.DMG.targetOs()).isEqualTo("mac");
        assertThat(JpackageInstallerArguments.Type.DEB.targetOs()).isEqualTo("linux");
        assertThat(JpackageInstallerArguments.Type.EXE.targetOs()).isEqualTo("windows");
    }

    @Test
    void missingRequiredFieldsFail() {
        assertThatThrownBy(() -> JpackageInstallerArguments.builder()
                .type(JpackageInstallerArguments.Type.DEB)
                .appImage(Path.of("x"))
                .destination(Path.of("y"))
                .appVersion("1.0.0")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }
}
