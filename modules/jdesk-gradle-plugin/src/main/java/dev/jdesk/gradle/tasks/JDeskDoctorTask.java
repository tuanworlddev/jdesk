package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.OsSupport;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * {@code jdeskDoctor}: verifies the JDK toolchain, OS/arch, WebView runtime presence,
 * frontend build tool availability, packaging tools (jlink/jpackage) and the
 * {@code jdesk} extension configuration. Collects <em>every</em> problem before failing
 * so one run reports the complete remediation list. Performs no downloads.
 */
@UntrackedTask(because = "environment diagnostics must run every time")
public abstract class JDeskDoctorTask extends DefaultTask {

    private static final Pattern REVERSE_DNS =
            Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    /** WebView2 Evergreen Runtime client GUID (stable, documented by Microsoft). */
    private static final String WEBVIEW2_CLIENT_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";

    @Input
    @Optional
    public abstract Property<String> getApplicationId();

    @Input
    @Optional
    public abstract Property<String> getMainClass();

    /** First element of {@code jdesk.frontend.buildCommand}; absent = no frontend. */
    @Input
    @Optional
    public abstract Property<String> getFrontendTool();

    @Input
    public abstract Property<Integer> getToolchainVersion();

    @Internal
    public abstract Property<String> getToolchainHome();

    /** Optional explicit WebView2Loader.dll path (Windows only). */
    @Input
    @Optional
    public abstract Property<String> getWebView2Loader();

    @TaskAction
    public void doctor() {
        List<String> report = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        checkJdk(report, problems);
        checkOs(report);
        checkWebView(report, problems);
        checkFrontendTool(report, problems);
        checkExtension(report, problems);

        StringBuilder text = new StringBuilder("JDesk doctor report\n");
        for (String line : report) {
            text.append("  ").append(line).append('\n');
        }
        getLogger().lifecycle(text.toString().stripTrailing());

        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("jdeskDoctor found ")
                    .append(problems.size()).append(" problem(s):\n");
            for (String problem : problems) {
                message.append("  - ").append(problem).append('\n');
            }
            throw new GradleException(message.toString().stripTrailing());
        }
        getLogger().lifecycle("jdeskDoctor: environment OK");
    }

    private void checkJdk(List<String> report, List<String> problems) {
        int version = getToolchainVersion().get();
        String home = getToolchainHome().get();
        if (version >= 25) {
            report.add("OK       JDK toolchain " + version + " at " + home);
        } else {
            report.add("PROBLEM  JDK toolchain " + version + " at " + home);
            problems.add("JDesk requires JDK 25 or newer; the configured toolchain is "
                    + version + ". Configure java { toolchain { languageVersion ="
                    + " JavaLanguageVersion.of(25) } }.");
        }
        for (String tool : List.of("jlink", "jpackage")) {
            Path located = dev.jdesk.packager.JdkTools.locateOrNull(Path.of(home), tool);
            if (located != null) {
                report.add("OK       packaging tool " + tool + ": " + located);
            } else {
                report.add("PROBLEM  packaging tool " + tool + " missing");
                problems.add("'" + tool + "' was not found under " + home + "/bin."
                        + " Install a full JDK 25+ (a JRE does not ship " + tool + ").");
            }
        }
    }

    private void checkOs(List<String> report) {
        report.add("REPORT   OS " + OsSupport.osName() + " "
                + System.getProperty("os.version", "?") + " (" + OsSupport.osArch() + ")");
    }

    private void checkWebView(List<String> report, List<String> problems) {
        if (OsSupport.isMacOs()) {
            Path webKit = Path.of("/System/Library/Frameworks/WebKit.framework");
            if (Files.isDirectory(webKit)) {
                report.add("OK       WebView runtime: WebKit.framework present");
            } else {
                report.add("PROBLEM  WebView runtime: WebKit.framework missing");
                problems.add("WebKit.framework was not found at " + webKit + ". JDesk"
                        + " requires the system WebKit framework (part of macOS).");
            }
            report.add("N/A      WebView2 check: not applicable on macOS");
            return;
        }
        if (OsSupport.isWindows()) {
            report.add("N/A      WebKit.framework check: not applicable on Windows");
            String loader = getWebView2Loader().getOrNull();
            if (loader != null) {
                if (Files.isRegularFile(Path.of(loader))) {
                    report.add("OK       WebView2Loader: " + loader);
                } else {
                    report.add("PROBLEM  WebView2Loader missing: " + loader);
                    problems.add("Configured WebView2Loader does not exist: " + loader
                            + ". Fix the jdeskWebView2Loader Gradle property.");
                }
            }
            if (webView2RuntimeRegistered()) {
                report.add("OK       WebView2 Evergreen Runtime registered");
            } else {
                report.add("PROBLEM  WebView2 Evergreen Runtime not detected");
                problems.add("The WebView2 Evergreen Runtime was not found in the"
                        + " registry. Install it from"
                        + " https://developer.microsoft.com/microsoft-edge/webview2/.");
            }
            return;
        }
        report.add("N/A      WebKit.framework / WebView2 checks: not applicable on "
                + OsSupport.osName());
        report.add("REPORT   Linux WebView (webkit2gtk) check lands with the Linux"
                + " adapter (Phase 5); not checked");
    }

    private boolean webView2RuntimeRegistered() {
        List<String> keys = List.of(
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\" + WEBVIEW2_CLIENT_GUID,
                "HKLM\\SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\" + WEBVIEW2_CLIENT_GUID,
                "HKCU\\SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\" + WEBVIEW2_CLIENT_GUID);
        for (String key : keys) {
            try {
                Process process = new ProcessBuilder("reg", "query", key, "/v", "pv")
                        .redirectErrorStream(true)
                        .start();
                process.getInputStream().readAllBytes();
                if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return true;
                }
                process.destroyForcibly();
            } catch (IOException e) {
                getLogger().info("reg query failed for {}: {}", key, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void checkFrontendTool(List<String> report, List<String> problems) {
        String tool = getFrontendTool().getOrNull();
        if (tool == null) {
            report.add("N/A      frontend build tool: no frontend configured");
            return;
        }
        File asFile = new File(tool);
        boolean found;
        if (tool.contains("/") || tool.contains(File.separator)) {
            found = asFile.isAbsolute() ? asFile.canExecute() : true;
            // Relative paths are resolved against the frontend directory at execution
            // time; only absolute paths can be validated here.
            if (asFile.isAbsolute()) {
                report.add((found ? "OK       " : "PROBLEM  ") + "frontend build tool: " + tool);
            } else {
                report.add("REPORT   frontend build tool is a relative path: " + tool
                        + " (resolved when jdeskFrontendBuild runs)");
            }
        } else {
            found = findOnPath(tool) != null;
            report.add((found ? "OK       " : "PROBLEM  ") + "frontend build tool '" + tool
                    + "'" + (found ? " found on PATH" : " not found on PATH"));
        }
        if (!found) {
            problems.add("Frontend build tool '" + tool + "' is not available. Install it"
                    + " or adjust jdesk.frontend.buildCommand.");
        }
    }

    private static Path findOnPath(String tool) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(tool);
        if (OsSupport.isWindows()) {
            String pathExt = System.getenv("PATHEXT");
            String[] extensions = pathExt != null
                    ? pathExt.split(";")
                    : new String[] {".EXE", ".BAT", ".CMD"};
            for (String ext : extensions) {
                if (!ext.isBlank()) {
                    candidates.add(tool + ext.toLowerCase(java.util.Locale.ROOT));
                    candidates.add(tool + ext);
                }
            }
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                Path path = Path.of(dir, candidate);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    private void checkExtension(List<String> report, List<String> problems) {
        String applicationId = getApplicationId().getOrNull();
        if (applicationId == null || applicationId.isBlank()) {
            report.add("PROBLEM  jdesk.applicationId is not set");
            problems.add("jdesk.applicationId is not set. Set a reverse-DNS id, e.g."
                    + " jdesk { applicationId.set(\"dev.example.app\") }.");
        } else if (!REVERSE_DNS.matcher(applicationId).matches()) {
            report.add("PROBLEM  jdesk.applicationId invalid: '" + applicationId + "'");
            problems.add("jdesk.applicationId '" + applicationId + "' is not a"
                    + " reverse-DNS identifier (expected lowercase dot-separated"
                    + " segments like \"dev.example.app\").");
        } else {
            report.add("OK       jdesk.applicationId " + applicationId);
        }

        String mainClass = getMainClass().getOrNull();
        if (mainClass == null || mainClass.isBlank()) {
            report.add("PROBLEM  jdesk.mainClass is not set");
            problems.add("jdesk.mainClass is not set. Set the fully qualified main"
                    + " class, e.g. jdesk { mainClass.set(\"dev.example.App\") }.");
        } else if (!CLASS_NAME.matcher(mainClass).matches()) {
            report.add("PROBLEM  jdesk.mainClass invalid: '" + mainClass + "'");
            problems.add("jdesk.mainClass '" + mainClass + "' is not a valid fully"
                    + " qualified Java class name.");
        } else {
            report.add("OK       jdesk.mainClass " + mainClass);
        }
    }
}
