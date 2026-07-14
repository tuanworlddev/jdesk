package dev.jdesk.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.gradle.tasks.JDeskPackageTask;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code jdeskPackage} image name (which becomes jpackage {@code --name} and thus the
 * packaged {@code CFBundleName} / bold macOS menu-bar title) resolves from the new
 * {@code applicationName} when set, falling back to the last {@code applicationId} segment.
 */
class JDeskPackageImageNameTest {

    private static JDeskExtension applyPlugin(Project project) {
        project.getPluginManager().apply(JDeskApplicationPlugin.class);
        return project.getExtensions().getByType(JDeskExtension.class);
    }

    private static JDeskPackageTask packageTask(Project project) {
        return (JDeskPackageTask) project.getTasks().getByName("jdeskPackage");
    }

    @Test
    void applicationNameDrivesImageNameWhenSet() {
        Project project = ProjectBuilder.builder().build();
        JDeskExtension jdesk = applyPlugin(project);
        jdesk.getApplicationId().set("dev.example.dragon7");
        jdesk.getApplicationName().set("Dragon 7");
        assertThat(packageTask(project).getImageName().get()).isEqualTo("Dragon 7");
    }

    @Test
    void fallsBackToLastApplicationIdSegmentWhenNameUnset() {
        Project project = ProjectBuilder.builder().build();
        JDeskExtension jdesk = applyPlugin(project);
        jdesk.getApplicationId().set("dev.example.dragon7");
        assertThat(packageTask(project).getImageName().get()).isEqualTo("dragon7");
    }
}
