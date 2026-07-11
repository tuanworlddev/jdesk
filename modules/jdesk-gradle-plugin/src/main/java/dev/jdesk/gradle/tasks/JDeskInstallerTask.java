package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.packager.JdkTools;
import dev.jdesk.packager.JpackageArguments;
import dev.jdesk.packager.JpackageInstallerArguments;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/**
 * {@code jdeskInstaller}: builds a platform installer (DMG/PKG, MSI/EXE, DEB/RPM) from the
 * {@code jdeskPackage} application image via {@code jpackage}, on the target OS only
 * (spec section 16.2). Unsigned unless a signing identity is configured; unsigned
 * installers do not satisfy a signed-release gate (spec 16.3).
 */
@DisableCachingByDefault(because = "produces a platform-specific installer")
public abstract class JDeskInstallerTask extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getAppImageDirectory();

    /** Image/launcher name (matches jdeskPackage). */
    @Input
    public abstract Property<String> getImageName();

    @Input
    public abstract Property<String> getAppVersion();

    /** Installer type override (dmg/pkg/msi/exe/deb/rpm); defaults to the OS default. */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getInstallerType();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getMacSigningIdentity();

    @Internal
    public abstract Property<String> getJavaHome();

    @OutputDirectory
    public abstract DirectoryProperty getDestination();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void buildInstaller() {
        boolean macOs = OsSupport.isMacOs();
        String osName = System.getProperty("os.name", "");
        JpackageInstallerArguments.Type type = resolveType(osName);

        String rawImageName = getImageName().get();
        File appImageRoot = new File(getAppImageDirectory().get().getAsFile(),
                macOs ? rawImageName + ".app" : rawImageName);
        if (!appImageRoot.isDirectory()) {
            throw new GradleException("jdeskInstaller: application image not found at "
                    + appImageRoot + ". Run jdeskPackage first.");
        }

        String version = JpackageArguments.normalizeVersion(getAppVersion().get(), macOs);
        File destination = getDestination().get().getAsFile();
        destination.mkdirs();

        List<String> args = JpackageInstallerArguments.builder()
                .type(type)
                .name(rawImageName)
                .appImage(appImageRoot.toPath())
                .destination(destination.toPath())
                .appVersion(version)
                .macSigningIdentity(getMacSigningIdentity().getOrNull())
                .build()
                .toArguments();

        Path jpackage;
        try {
            jpackage = JdkTools.locate(Path.of(getJavaHome().get()), "jpackage");
        } catch (IllegalStateException e) {
            throw new GradleException("jdeskInstaller: " + e.getMessage(), e);
        }
        List<String> commandLine = new ArrayList<>();
        commandLine.add(jpackage.toString());
        commandLine.addAll(args);
        boolean signed = macOs && getMacSigningIdentity().isPresent();
        getLogger().lifecycle("jdeskInstaller: {} {}", String.join(" ", commandLine),
                signed ? "(signed)" : "(UNSIGNED)");
        try {
            getExecOperations().exec(spec -> spec.setCommandLine(commandLine));
        } catch (Exception e) {
            throw new GradleException("jdeskInstaller: jpackage failed. On Windows an MSI"
                    + " needs the WiX Toolset on PATH; on Linux a DEB needs dpkg/fakeroot."
                    + " Run with --info for jpackage output.", e);
        }
        getLogger().lifecycle("jdeskInstaller: {} installer written to {}{}",
                type.jpackageType(), destination, signed ? "" : " (UNSIGNED)");
    }

    private JpackageInstallerArguments.Type resolveType(String osName) {
        String override = getInstallerType().getOrNull();
        if (override != null) {
            try {
                return JpackageInstallerArguments.Type.valueOf(
                        override.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new GradleException("jdeskInstaller: unknown installer type '"
                        + override + "'. Use dmg/pkg/msi/exe/deb/rpm.");
            }
        }
        return JpackageInstallerArguments.defaultForOs(osName).orElseThrow(() ->
                new GradleException("jdeskInstaller: no default installer type for OS '"
                        + osName + "'. Set jdesk installer type explicitly."));
    }
}
