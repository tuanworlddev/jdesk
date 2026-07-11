package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.packager.JdkTools;
import dev.jdesk.packager.JpackageArguments;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/**
 * {@code jdeskPackage}: produces a platform application image with the toolchain's
 * {@code jpackage}, combining the {@code jdeskRuntimeImage} output with the application
 * jars (staged into a private input directory). Only {@code --type app-image} is
 * supported here; installer types (MSI/DMG/DEB) land in Phase 7 via
 * {@code jdeskInstaller}.
 */
@DisableCachingByDefault(because = "packages a platform-specific application image")
public abstract class JDeskPackageTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRuntimeImage();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getMainJar();

    /** Dependency jars staged next to the main jar (jpackage {@code --input}). */
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspathJars();

    @Input
    public abstract Property<String> getMainClass();

    @Input
    @Optional
    public abstract Property<String> getApplicationId();

    /** Image (and launcher) name; defaults to the last applicationId segment. */
    @Input
    public abstract Property<String> getImageName();

    @Input
    public abstract Property<String> getAppVersion();

    @Internal
    public abstract Property<String> getJavaHome();

    @Internal
    public abstract DirectoryProperty getStagingDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getDestination();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void packageApplication() {
        if (!getMainClass().isPresent()) {
            throw new GradleException("jdeskPackage: jdesk.mainClass is not set. Set it,"
                    + " e.g. jdesk { mainClass.set(\"dev.example.App\") }.");
        }
        File mainJar = getMainJar().get().getAsFile();
        File staging = getStagingDirectory().get().getAsFile();
        getFileSystemOperations().sync(spec -> {
            spec.from(mainJar);
            spec.from(getRuntimeClasspathJars().filter(File::isFile));
            spec.into(staging);
        });

        String name = getImageName().get();
        File destination = getDestination().get().getAsFile();
        // jpackage refuses to overwrite an existing image.
        getFileSystemOperations().delete(spec -> spec.delete(
                new File(destination, name),
                new File(destination, name + ".app")));

        boolean macOs = OsSupport.isMacOs();
        String rawVersion = getAppVersion().get();
        String version = JpackageArguments.normalizeVersion(rawVersion, macOs);
        if (!version.equals(rawVersion)) {
            getLogger().warn("jdeskPackage: app version '{}' normalized to '{}' for"
                    + " jpackage{}", rawVersion, version,
                    macOs ? " (macOS forbids a 0 major version)" : "");
        }

        JpackageArguments.Builder builder = JpackageArguments.builder()
                .name(name)
                .input(staging.toPath())
                .mainJar(mainJar.getName())
                .mainClass(getMainClass().get())
                .runtimeImage(getRuntimeImage().get().getAsFile().toPath())
                .destination(destination.toPath())
                .appVersion(version)
                // The runtime image already embeds the option via jlink --add-options;
                // passing it here as well keeps the launcher explicit and greppable.
                .javaOption("--enable-native-access=ALL-UNNAMED");
        if (macOs) {
            builder.javaOption("-XstartOnFirstThread");
            String applicationId = getApplicationId().getOrNull();
            if (applicationId != null) {
                builder.macPackageIdentifier(applicationId);
            }
        }
        List<String> args = builder.build().toArguments();

        Path jpackage;
        try {
            jpackage = JdkTools.locate(Path.of(getJavaHome().get()), "jpackage");
        } catch (IllegalStateException e) {
            throw new GradleException("jdeskPackage: " + e.getMessage(), e);
        }
        List<String> commandLine = new ArrayList<>();
        commandLine.add(jpackage.toString());
        commandLine.addAll(args);
        getLogger().lifecycle("jdeskPackage: {}", String.join(" ", commandLine));
        try {
            getExecOperations().exec(spec -> spec.setCommandLine(commandLine));
        } catch (Exception e) {
            throw new GradleException("jdeskPackage: jpackage failed. Run with --info"
                    + " for its output; verify the runtime image and jars above.", e);
        }
        getLogger().lifecycle("jdeskPackage: app image written to {}", destination);
    }
}
