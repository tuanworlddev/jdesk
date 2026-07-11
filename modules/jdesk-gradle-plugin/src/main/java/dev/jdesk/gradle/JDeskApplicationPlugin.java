package dev.jdesk.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** JDesk application plugin. Tasks land in Phase 3; Phase 0 registers only a stub doctor. */
public class JDeskApplicationPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().register("jdeskDoctor", task -> {
            task.setGroup("jdesk");
            task.setDescription("Verifies JDK, OS libraries, WebView runtime and configuration.");
            task.doLast(t -> {
                throw new org.gradle.api.GradleException(
                        "jdeskDoctor is not implemented yet (Phase 3). This stub fails on purpose;"
                                + " it must never report a passing environment it did not check.");
            });
        });
    }
}
