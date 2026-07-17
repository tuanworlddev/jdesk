package dev.jdesk.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.gradle.tasks.JDeskInstallerTask;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/** Proves every public signing DSL value reaches the installer task. */
class JDeskInstallerSigningWiringTest {

    @Test
    void wiresEveryPlatformSigningProperty() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply(JDeskApplicationPlugin.class);
        JDeskExtension jdesk = project.getExtensions().getByType(JDeskExtension.class);
        jdesk.getApplicationId().set("dev.example.signed");
        jdesk.getSigning().getWindowsCertificate().set("0123456789ABCDEF0123456789ABCDEF01234567");
        jdesk.getSigning().getWindowsTimestampUrl().set("https://timestamp.example");
        jdesk.getSigning().getMacSigningIdentity().set("Developer ID Application: Example");
        jdesk.getSigning().getMacNotarizationProfile().set("example-notary");
        jdesk.getSigning().getLinuxSigningKey().set("0xA26BE8AA8FA0492F");
        jdesk.getSigning().getLinuxSigningPassphrase().set("test-only-passphrase");

        JDeskInstallerTask task = (JDeskInstallerTask) project.getTasks()
                .getByName("jdeskInstaller");
        assertThat(task.getWindowsCertificate().get())
                .isEqualTo("0123456789ABCDEF0123456789ABCDEF01234567");
        assertThat(task.getWindowsTimestampUrl().get()).isEqualTo("https://timestamp.example");
        assertThat(task.getMacSigningIdentity().get())
                .isEqualTo("Developer ID Application: Example");
        assertThat(task.getMacNotarizationProfile().get()).isEqualTo("example-notary");
        assertThat(task.getLinuxSigningKey().get()).isEqualTo("0xA26BE8AA8FA0492F");
        assertThat(task.getLinuxSigningPassphrase().get()).isEqualTo("test-only-passphrase");
    }
}
