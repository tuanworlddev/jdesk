package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.packager.JdkTools;
import dev.jdesk.packager.JpackageArguments;
import dev.jdesk.packager.JpackageInstallerArguments;
import dev.jdesk.packager.SigningCommands;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
    public abstract Property<String> getWindowsCertificate();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getWindowsTimestampUrl();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getMacSigningIdentity();

    /** macOS notarization keychain profile; when set (with a signing identity) the built
     *  installer is submitted to Apple notarization and the ticket is stapled. */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getMacNotarizationProfile();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getLinuxSigningKey();

    /** Secret input is deliberately excluded from task fingerprints and command lines. */
    @Internal
    public abstract Property<String> getLinuxSigningPassphrase();

    @Internal
    public abstract Property<String> getJavaHome();

    @OutputDirectory
    public abstract DirectoryProperty getDestination();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void buildInstaller() {
        boolean macOs = OsSupport.isMacOs();
        boolean windows = OsSupport.isWindows();
        boolean linux = OsSupport.isLinux();
        String osName = System.getProperty("os.name", "");
        JpackageInstallerArguments.Type type = resolveType(osName);

        boolean macSigned = macOs && getMacSigningIdentity().isPresent();
        boolean windowsSigned = windows && getWindowsCertificate().isPresent();
        boolean linuxSigned = linux && getLinuxSigningKey().isPresent();
        if (macOs && getMacNotarizationProfile().isPresent()
                && !getMacSigningIdentity().isPresent()) {
            throw new GradleException("jdeskInstaller: macNotarizationProfile requires"
                    + " macSigningIdentity; refusing to produce an unnotarizable artifact.");
        }
        if (windowsSigned && !getWindowsTimestampUrl().isPresent()) {
            throw new GradleException("jdeskInstaller: windowsCertificate requires"
                    + " windowsTimestampUrl so the Authenticode signature remains valid"
                    + " after certificate expiry.");
        }
        if (linux && getLinuxSigningPassphrase().isPresent() && !linuxSigned) {
            throw new GradleException("jdeskInstaller: linuxSigningPassphrase requires"
                    + " linuxSigningKey; refusing an unused secret configuration.");
        }

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
        boolean signed = macSigned || windowsSigned || linuxSigned;
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

        Path installer = locateInstaller(destination, type);

        if (windowsSigned) {
            getLogger().lifecycle("jdeskInstaller: Authenticode-signing {}", installer);
            runToolchain(SigningCommands.windowsSigntool(installer,
                            getWindowsCertificate().get(), getWindowsTimestampUrl().get()),
                    "signtool sign", "Verify the certificate is installed in the runner's"
                            + " certificate store and the timestamp service is reachable.");
            runToolchain(SigningCommands.windowsVerify(installer), "signtool verify",
                    "The produced installer did not pass Authenticode policy verification.");
        }

        if (linuxSigned) {
            getLogger().lifecycle("jdeskInstaller: detached-signing {}", installer);
            String passphrase = getLinuxSigningPassphrase().getOrNull();
            List<String> signCommand = passphrase == null
                    ? SigningCommands.linuxGpgDetachSign(installer, getLinuxSigningKey().get())
                    : SigningCommands.linuxGpgDetachSignHeadless(
                            installer, getLinuxSigningKey().get());
            runToolchain(signCommand, "gpg detach-sign",
                    "Verify the configured Linux signing key is available to gpg.", passphrase);
            runToolchain(SigningCommands.linuxGpgVerify(installer), "gpg verify",
                    "The produced detached package signature did not verify.");
        }

        // Notarize + staple: Gatekeeper requires an outside-App-Store macOS installer to be
        // notarized. jpackage already code-signed the image (Hardened Runtime); here we submit
        // the built installer to Apple and staple the ticket so it launches offline.
        if (macSigned && getMacNotarizationProfile().isPresent()) {
            String profile = getMacNotarizationProfile().get();
            getLogger().lifecycle("jdeskInstaller: notarizing {} (profile {})", installer, profile);
            runToolchain(SigningCommands.macNotarize(installer, profile), "notarytool submit",
                    "Verify the notarization keychain profile and Apple credentials.");
            runToolchain(SigningCommands.macStaple(installer), "stapler staple",
                    "Apple accepted the artifact but its notarization ticket could not be stapled.");
            runToolchain(SigningCommands.macStapleValidate(installer), "stapler validate",
                    "The stapled notarization ticket did not validate.");
            getLogger().lifecycle("jdeskInstaller: notarized and stapled {}", installer);
        }
    }

    /** Finds the single installer jpackage produced for {@code type} in {@code destination}. */
    private static Path locateInstaller(File destination, JpackageInstallerArguments.Type type) {
        String suffix = "." + type.jpackageType();
        File[] matches = destination.listFiles((dir, name) -> name.endsWith(suffix));
        if (matches == null || matches.length == 0) {
            throw new GradleException("jdeskInstaller: no " + suffix
                    + " installer found in " + destination + " to notarize");
        }
        return matches[0].toPath();
    }

    private void runToolchain(List<String> commandLine, String label, String remediation) {
        runToolchain(commandLine, label, remediation, null);
    }

    private void runToolchain(List<String> commandLine, String label, String remediation,
            String standardInput) {
        try {
            getExecOperations().exec(spec -> {
                spec.setCommandLine(commandLine);
                if (standardInput != null) {
                    spec.setStandardInput(new ByteArrayInputStream(
                            (standardInput + System.lineSeparator())
                                    .getBytes(StandardCharsets.UTF_8)));
                }
            });
        } catch (Exception e) {
            throw new GradleException("jdeskInstaller: " + label + " failed. " + remediation, e);
        }
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
