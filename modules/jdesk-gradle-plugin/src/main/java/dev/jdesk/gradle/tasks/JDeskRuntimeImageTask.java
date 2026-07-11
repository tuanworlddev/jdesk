package dev.jdesk.gradle.tasks;

import dev.jdesk.packager.JdepsArguments;
import dev.jdesk.packager.JdkTools;
import dev.jdesk.packager.JlinkArguments;
import dev.jdesk.packager.ModuleDeps;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecResult;
import org.gradle.work.DisableCachingByDefault;

/**
 * {@code jdeskRuntimeImage}: builds a trimmed JDK runtime image with the toolchain's
 * {@code jlink}. The module set is computed by running the toolchain's {@code jdeps
 * --print-module-deps} over the application's runtime classpath (declared tool run, no
 * hidden downloads).
 *
 * <p>The image contains the required JDK modules. Application and framework modules are
 * supplied to jpackage separately; native access is granted by the packaged modular
 * launcher, never globally in the runtime image.
 */
@DisableCachingByDefault(because = "produces a full runtime image; caching would copy gigabytes")
public abstract class JDeskRuntimeImageTask extends DefaultTask {

    /** Application runtime classpath (app jar + dependency jars). */
    @Classpath
    public abstract ConfigurableFileCollection getAppClasspath();

    @Internal
    public abstract Property<String> getJavaHome();

    /** Feature release passed to {@code jdeps --multi-release} (the toolchain version). */
    @Input
    public abstract Property<Integer> getMultiRelease();

    /** Extra JDK modules to add beyond what jdeps reports (e.g. jdk.crypto.ec). */
    @Input
    public abstract ListProperty<String> getAdditionalModules();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract org.gradle.process.ExecOperations getExecOperations();

    @TaskAction
    public void createRuntimeImage() throws IOException {
        Path javaHome = Path.of(getJavaHome().get());
        List<Path> entries = getAppClasspath().getFiles().stream()
                .filter(File::exists)
                .map(File::toPath)
                .sorted()
                .toList();
        if (entries.isEmpty()) {
            throw new GradleException("jdeskRuntimeImage: the application classpath is"
                    + " empty; nothing to analyze. Does the project produce a jar?");
        }

        Set<String> modules = new TreeSet<>(computeModules(javaHome, entries));
        modules.add("java.base");
        modules.addAll(getAdditionalModules().getOrElse(List.of()));
        getLogger().lifecycle("jdeskRuntimeImage: JDK modules = {}", String.join(",", modules));

        Path output = getOutputDirectory().get().getAsFile().toPath();
        deleteRecursively(output); // jlink refuses to write into an existing directory

        Path jlink = locateTool(javaHome, "jlink");
        List<String> args = JlinkArguments.builder()
                .modules(modules)
                .output(output)
                .build()
                .toArguments();
        runTool(jlink, args, null);

        if (JdkTools.locateOrNull(output, "java") == null) {
            throw new GradleException("jdeskRuntimeImage: jlink reported success but "
                    + output + " contains no bin/java. Inspect the jlink output above.");
        }
        getLogger().lifecycle("jdeskRuntimeImage: runtime image written to {}", output);
    }

    private Set<String> computeModules(Path javaHome, List<Path> entries) {
        Path jdeps = locateTool(javaHome, "jdeps");
        List<String> args = JdepsArguments.builder()
                .multiRelease(getMultiRelease().get())
                .classPath(entries)
                .roots(entries)
                .build()
                .toArguments();
        String stdout = runTool(jdeps, args, "jdeps could not analyze the runtime"
                + " classpath. Fix the reported jar problem or add the missing JDK"
                + " modules via jdeskRuntimeImage.additionalModules.");
        try {
            return ModuleDeps.parse(stdout);
        } catch (IllegalArgumentException e) {
            throw new GradleException("jdeskRuntimeImage: " + e.getMessage(), e);
        }
    }

    private Path locateTool(Path javaHome, String tool) {
        try {
            return JdkTools.locate(javaHome, tool);
        } catch (IllegalStateException e) {
            throw new GradleException("jdeskRuntimeImage: " + e.getMessage(), e);
        }
    }

    private String runTool(Path tool, List<String> args, String remediation) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        getLogger().info("jdeskRuntimeImage: {} {}", tool, String.join(" ", args));
        ExecResult result = getExecOperations().exec(spec -> {
            List<String> commandLine = new ArrayList<>();
            commandLine.add(tool.toString());
            commandLine.addAll(args);
            spec.setCommandLine(commandLine);
            spec.setStandardOutput(stdout);
            spec.setErrorOutput(stderr);
            spec.setIgnoreExitValue(true);
        });
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        if (result.getExitValue() != 0) {
            throw new GradleException("jdeskRuntimeImage: " + tool.getFileName()
                    + " failed with exit code " + result.getExitValue() + ".\n"
                    + (remediation == null ? "" : remediation + "\n")
                    + "stdout:\n" + out + "\nstderr:\n" + err);
        }
        if (!err.isBlank()) {
            getLogger().info("jdeskRuntimeImage: {} stderr: {}", tool.getFileName(), err);
        }
        return out;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
