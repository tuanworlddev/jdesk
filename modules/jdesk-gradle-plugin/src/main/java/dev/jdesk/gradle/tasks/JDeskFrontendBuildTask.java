package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.EnvRedactor;
import java.io.File;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/**
 * {@code jdeskFrontendBuild}: runs {@code jdesk.frontend.buildCommand} inside the
 * frontend directory, producing {@code distDirectory}. Inputs are the frontend sources
 * (minus {@code node_modules}, {@code .git} and the dist directory itself), so the task
 * is skipped when nothing changed and reports NO-SOURCE when no frontend is configured.
 *
 * <p>The command is executed as an argument list (never shell string concatenation), so
 * paths containing spaces and non-ASCII characters work. Environment variables matching
 * {@code (?i)(token|secret|password|key)} are redacted from any logged environment.
 */
@DisableCachingByDefault(because = "executes an external frontend toolchain")
public abstract class JDeskFrontendBuildTask extends DefaultTask {

    /** Frontend sources; empty (e.g. unconfigured frontend) skips the task with NO-SOURCE. */
    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    @Input
    @Optional
    public abstract ListProperty<String> getBuildCommand();

    /** When true, copy the frontend tree into dist instead of running the build command. */
    @Input
    @Optional
    public abstract org.gradle.api.provider.Property<Boolean> getStaticCopy();

    @Internal
    public abstract DirectoryProperty getFrontendDirectory();

    @OutputDirectory
    @Optional
    public abstract DirectoryProperty getDistDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void buildFrontend() {
        if (!getFrontendDirectory().isPresent()) {
            throw new GradleException("jdeskFrontendBuild: jdesk.frontend.directory is"
                    + " not set. Point it at the frontend project, e.g."
                    + " directory.set(layout.projectDirectory.dir(\"ui\")).");
        }
        if (Boolean.TRUE.equals(getStaticCopy().getOrElse(false))) {
            staticCopy();
            return;
        }
        List<String> command = getBuildCommand().getOrElse(List.of());
        if (command.isEmpty()) {
            throw new GradleException("jdeskFrontendBuild: frontend sources were found"
                    + " but jdesk.frontend.buildCommand is empty. Set it, e.g."
                    + " buildCommand.set(listOf(\"npm\", \"run\", \"build\")), or enable"
                    + " frontend { staticCopy() } for a no-bundler app.");
        }
        File workingDir = getFrontendDirectory().get().getAsFile();
        getLogger().lifecycle("jdeskFrontendBuild: {} (in {})", command, workingDir);
        getLogger().info("jdeskFrontendBuild environment (redacted): {}",
                EnvRedactor.redact(System.getenv()));
        try {
            getExecOperations().exec(spec -> {
                spec.setCommandLine(command);
                spec.setWorkingDir(workingDir);
            });
        } catch (Exception e) {
            throw new GradleException("jdeskFrontendBuild: command " + command
                    + " failed in " + workingDir + ". Run with --info for the tool"
                    + " output; verify the command works standalone in that directory.", e);
        }
        File dist = getDistDirectory().isPresent()
                ? getDistDirectory().get().getAsFile()
                : null;
        if (dist == null) {
            throw new GradleException("jdeskFrontendBuild: jdesk.frontend.distDirectory"
                    + " is not set; set it to where the build command writes its output.");
        }
        if (!dist.isDirectory()) {
            throw new GradleException("jdeskFrontendBuild: the build command succeeded"
                    + " but did not create " + dist + ". Align"
                    + " jdesk.frontend.distDirectory with the tool's output directory.");
        }
    }

    private void staticCopy() {
        if (!getDistDirectory().isPresent()) {
            throw new GradleException("jdeskFrontendBuild: staticCopy needs"
                    + " jdesk.frontend.distDirectory set (default is directory/dist).");
        }
        java.nio.file.Path source = getFrontendDirectory().get().getAsFile().toPath();
        java.nio.file.Path dist = getDistDirectory().get().getAsFile().toPath();
        try {
            dev.jdesk.gradle.internal.StaticCopy.copy(source, dist);
            getLogger().lifecycle("jdeskFrontendBuild: static copy {} -> {}", source, dist);
        } catch (java.io.IOException | RuntimeException e) {
            throw new GradleException("jdeskFrontendBuild: static copy failed", e);
        }
    }
}
