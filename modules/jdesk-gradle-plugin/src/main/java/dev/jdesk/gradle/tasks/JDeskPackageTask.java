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
 * named modules (staged into a private module-path directory). This task produces the
 * {@code app-image}; {@code jdeskInstaller} produces target-OS installer formats.
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

    /** Named application module containing {@link #getMainClass()}. */
    @Input
    @Optional
    public abstract Property<String> getMainModule();

    @Input
    @Optional
    public abstract Property<String> getApplicationId();

    /** Image (and launcher) name; defaults to the last applicationId segment. */
    @Input
    public abstract Property<String> getImageName();

    @Input
    public abstract Property<String> getAppVersion();

    @Input
    @Optional
    public abstract org.gradle.api.provider.ListProperty<String> getUrlSchemes();

    @Input
    @Optional
    public abstract org.gradle.api.provider.MapProperty<String, String> getUsageDescriptions();

    /** File associations encoded as {@code extension\tmimeType\tdescription}. */
    @Input
    @Optional
    public abstract org.gradle.api.provider.ListProperty<String> getFileAssociations();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getAppIcon();

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
        boolean modular = getMainModule().isPresent() && !getMainModule().get().isBlank();
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
                .runtimeImage(getRuntimeImage().get().getAsFile().toPath())
                .destination(destination.toPath())
                .appVersion(version);
        if (modular) {
            // Named-module app: launch from the module path with narrow native access.
            builder.modulePath(staging.toPath())
                    .module(getMainModule().get(), getMainClass().get())
                    .javaOption("--enable-native-access=" + platformModule())
                    .javaOption("--illegal-native-access=deny")
                    .javaOption("-Djdesk.assets.module=" + getMainModule().get());
        } else {
            // Classpath app (no module-info): launch from --input jars with the main
            // class; native access is ALL-UNNAMED and assets load from the classpath.
            builder.input(staging.toPath())
                    .mainJar(mainJar.getName())
                    .mainClass(getMainClass().get())
                    .javaOption("--enable-native-access=ALL-UNNAMED")
                    .javaOption("-Djdesk.assets.classpath=web");
        }
        if (macOs) {
            builder.javaOption("-XstartOnFirstThread");
            String applicationId = getApplicationId().getOrNull();
            if (applicationId != null) {
                builder.macPackageIdentifier(applicationId);
            }
        }
        if (getAppIcon().isPresent()) {
            builder.icon(getAppIcon().get().getAsFile().toPath());
        }
        List<String> associations = getFileAssociations().getOrElse(List.of());
        if (!associations.isEmpty()) {
            try {
                Path associationsDir = staging.toPath().resolveSibling("jdesk-file-associations");
                java.nio.file.Files.createDirectories(associationsDir);
                int index = 0;
                for (String encoded : associations) {
                    String[] parts = encoded.split("\t", -1);
                    String ext = parts[0];
                    String mime = parts.length > 1 ? parts[1] : "";
                    String desc = parts.length > 2 ? parts[2] : ext;
                    Path props = associationsDir.resolve("assoc-" + (index++) + ".properties");
                    java.nio.file.Files.writeString(props, "extension=" + ext + "\n"
                            + "mime-type=" + mime + "\ndescription=" + desc + "\n");
                    builder.fileAssociation(props);
                }
            } catch (java.io.IOException e) {
                throw new GradleException("jdeskPackage: failed to write file-association files", e);
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

        // Register scheme:// deep links + usage descriptions that jpackage cannot express,
        // by post-processing the generated Info.plist (macOS Launch Services).
        List<String> schemes = getUrlSchemes().getOrElse(List.of());
        java.util.Map<String, String> usageDescriptions =
                getUsageDescriptions().getOrElse(java.util.Map.of());
        if (macOs && (!schemes.isEmpty() || !usageDescriptions.isEmpty())) {
            Path plist = new File(destination, name + ".app/Contents/Info.plist").toPath();
            try {
                if (java.nio.file.Files.exists(plist)) {
                    String customized = dev.jdesk.packager.InfoPlistCustomizer.customize(
                            java.nio.file.Files.readString(plist),
                            getApplicationId().getOrElse(name), schemes, usageDescriptions);
                    java.nio.file.Files.writeString(plist, customized);
                    getLogger().lifecycle("jdeskPackage: injected CFBundleURLTypes into Info.plist");
                }
            } catch (java.io.IOException e) {
                throw new GradleException("jdeskPackage: failed to customize Info.plist", e);
            }
        }

        // Release hygiene (spec 12.5, 16): SHA-256 checksums + CycloneDX SBOM over the
        // produced image. CI images are UNSIGNED and do not satisfy a signed-release gate.
        try {
            Path imageRoot = new File(destination, macOs ? name + ".app" : name).toPath();
            Path root = java.nio.file.Files.isDirectory(imageRoot)
                    ? imageRoot : destination.toPath();
            List<dev.jdesk.packager.ReleaseArtifacts.Checksum> checksums =
                    dev.jdesk.packager.ReleaseArtifacts.writeChecksums(
                            root, destination.toPath().resolve("checksums.sha256"));
            dev.jdesk.packager.ReleaseArtifacts.writeSbom(
                    destination.toPath().resolve("sbom.cyclonedx.json"),
                    getApplicationId().getOrElse(name), version, checksums);
            getLogger().lifecycle("jdeskPackage: wrote checksums.sha256 ({} files) and"
                    + " sbom.cyclonedx.json (UNSIGNED)", checksums.size());
        } catch (RuntimeException e) {
            throw new GradleException("jdeskPackage: failed to write checksums/SBOM", e);
        }
    }

    private static String platformModule() {
        if (OsSupport.isMacOs()) {
            return "dev.jdesk.platform.macos";
        }
        if (OsSupport.isWindows()) {
            return "dev.jdesk.platform.windows";
        }
        if (OsSupport.isLinux()) {
            return "dev.jdesk.platform.linux";
        }
        throw new GradleException("jdeskPackage: unsupported operating system "
                + OsSupport.osName());
    }
}
