package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.EnvRedactor;
import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.gradle.internal.ProcessTrees;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Interactive development supervisor. It owns the frontend dev server and Java process,
 * recompiles after Java/resource changes, and swaps the Java process only after a
 * successful build.
 */
@UntrackedTask(because = "interactive development session; never up-to-date")
public abstract class JDeskDevTask extends DefaultTask {

    private static final long POLL_MILLIS = 150L;

    @Internal
    public abstract ListProperty<String> getDevCommand();

    @Internal
    public abstract DirectoryProperty getFrontendDirectory();

    @Internal
    public abstract Property<String> getDevUrl();

    @Internal
    public abstract ListProperty<String> getFrontendBuildCommand();

    @Internal
    public abstract org.gradle.api.provider.Property<Boolean> getFrontendStaticCopy();

    @Internal
    public abstract DirectoryProperty getFrontendDistDirectory();

    @Internal
    public abstract ConfigurableFileCollection getFrontendSources();

    @Internal
    public abstract Property<String> getMainClass();

    @Internal
    public abstract Property<String> getMainModule();

    @Internal
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @Internal
    public abstract ConfigurableFileCollection getApplicationResources();

    @Internal
    public abstract Property<String> getJavaExecutable();

    @Internal
    public abstract Property<Integer> getProbeAttempts();

    @Internal
    public abstract Property<Integer> getProbeIntervalMillis();

    @Internal
    public abstract Property<Boolean> getJavaReload();

    @Internal
    public abstract ListProperty<String> getReloadCommand();

    @Internal
    public abstract Property<Integer> getReloadDebounceMillis();

    @Internal
    public abstract DirectoryProperty getReloadWorkingDirectory();

    @Internal
    public abstract ConfigurableFileCollection getReloadSources();

    @TaskAction
    public void dev() throws InterruptedException {
        if (!getMainClass().isPresent() || getMainClass().get().isBlank()) {
            throw new GradleException("jdeskDev: jdesk.mainClass is not set. Set it,"
                    + " e.g. jdesk { mainClass.set(\"dev.example.App\") }.");
        }
        validateReloadSettings();

        // No devCommand + a buildCommand = static frontend mode: rebuild the UI on
        // change and let the app's dev-mode asset watcher reload the page. No Node,
        // no dev server.
        boolean staticCopy = Boolean.TRUE.equals(getFrontendStaticCopy().getOrElse(false));
        boolean staticFrontend = getDevCommand().getOrElse(List.of()).isEmpty()
                && getFrontendDirectory().isPresent()
                && (staticCopy || !getFrontendBuildCommand().getOrElse(List.of()).isEmpty());

        Process frontend = null;
        Process application = null;
        Thread shutdownHook = null;
        AtomicReference<Process> applicationRef = new AtomicReference<>();
        try {
            frontend = startFrontend();
            if (staticFrontend) {
                getLogger().lifecycle("jdeskDev: static frontend mode (no devCommand);"
                        + " UI changes rebuild and reload in the running app");
                if (!runFrontendBuild()) {
                    throw new GradleException("jdeskDev: initial frontend build failed");
                }
            }
            Process frontendRef = frontend;
            shutdownHook = new Thread(() -> {
                Process activeApplication = applicationRef.get();
                if (activeApplication != null) {
                    ProcessTrees.destroy(activeApplication);
                }
                if (frontendRef != null) {
                    ProcessTrees.destroy(frontendRef);
                }
            }, "jdesk-dev-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            application = startApplication();
            applicationRef.set(application);
            boolean javaReload = getJavaReload().getOrElse(true);
            if (!javaReload && !staticFrontend) {
                requireSuccessfulExit(application);
                return;
            }

            String sourceState = javaReload ? sourceState() : "";
            String frontendState = staticFrontend ? frontendSourceState() : "";
            while (application.isAlive()) {
                Thread.sleep(POLL_MILLIS);
                if (staticFrontend) {
                    String nextFrontend = frontendSourceState();
                    if (!frontendState.equals(nextFrontend)) {
                        frontendState = awaitQuietState(nextFrontend, this::frontendSourceState);
                        getLogger().lifecycle("jdeskDev: frontend change detected; rebuilding UI");
                        if (runFrontendBuild()) {
                            getLogger().lifecycle(
                                    "jdeskDev: UI rebuilt; the app reloads it automatically");
                        } else {
                            getLogger().warn(
                                    "jdeskDev: frontend build failed; keeping the previous UI");
                        }
                        continue;
                    }
                }
                if (!javaReload) {
                    continue;
                }
                String nextState = sourceState();
                if (sourceState.equals(nextState)) {
                    continue;
                }
                sourceState = awaitQuietState(nextState, this::sourceState);
                getLogger().lifecycle("jdeskDev: source change detected; rebuilding");
                if (!runReloadCommand()) {
                    getLogger().warn("jdeskDev: rebuild failed; keeping the current app running");
                    continue;
                }
                getLogger().lifecycle("jdeskDev: rebuild succeeded; restarting application");
                ProcessTrees.destroy(application);
                application = startApplication();
                applicationRef.set(application);
            }
            requireSuccessfulExit(application);
        } finally {
            if (application != null && application.isAlive()) {
                ProcessTrees.destroy(application);
            }
            if (frontend != null) {
                getLogger().lifecycle("jdeskDev: stopping frontend dev server");
                ProcessTrees.destroy(frontend);
            }
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM already shutting down.
                }
            }
        }
    }

    private void validateReloadSettings() {
        int debounce = getReloadDebounceMillis().getOrElse(300);
        if (debounce < 0) {
            throw new GradleException("jdeskDev: development.reloadDebounceMillis must be >= 0");
        }
        if (getJavaReload().getOrElse(true) && getReloadCommand().getOrElse(List.of()).isEmpty()) {
            throw new GradleException("jdeskDev: Java reload is enabled but the reload command is empty");
        }
    }

    private Process startFrontend() throws InterruptedException {
        List<String> command = getDevCommand().getOrElse(List.of());
        if (command.isEmpty()) {
            return null;
        }
        if (!getFrontendDirectory().isPresent()) {
            throw new GradleException("jdeskDev: jdesk.frontend.devCommand is set but"
                    + " jdesk.frontend.directory is not set");
        }
        File directory = getFrontendDirectory().get().getAsFile();
        Process frontend = startProcess(command, directory, "frontend");
        try {
            String devUrl = getDevUrl().getOrNull();
            if (devUrl == null) {
                getLogger().warn("jdeskDev: jdesk.frontend.devUrl is not set; the app starts"
                        + " without waiting for the dev server");
            } else {
                waitForDevServer(devUrl, frontend);
            }
            return frontend;
        } catch (RuntimeException | InterruptedException e) {
            ProcessTrees.destroy(frontend);
            throw e;
        }
    }

    private Process startApplication() {
        boolean modular = getMainModule().isPresent() && !getMainModule().get().isBlank();
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable().get());
        if (modular) {
            String platformModule = platformModule();
            if (hasModule(platformModule)) {
                command.add("--add-modules=" + platformModule);
                command.add("--enable-native-access=" + platformModule);
            }
            command.add("--illegal-native-access=deny");
            String resourcePath = existingResourcePath();
            if (!resourcePath.isEmpty()) {
                command.add("--patch-module=" + getMainModule().get() + "=" + resourcePath);
            }
        } else {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        if (OsSupport.isMacOs()) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-Djdesk.dev=true");
        if (Boolean.getBoolean("jdesk.automation")) {
            command.add("-Djdesk.automation=true");
            String automationDirectory = System.getProperty("jdesk.automation.dir");
            if (automationDirectory != null && !automationDirectory.isBlank()) {
                command.add("-Djdesk.automation.dir=" + automationDirectory);
            }
        }
        String devUrl = getDevUrl().getOrNull();
        if (devUrl != null) {
            command.add("-Djdesk.devUrl=" + devUrl);
        } else if (getFrontendDistDirectory().isPresent()) {
            // Static frontend mode: serve the built UI from disk so the runtime's
            // dev-mode asset watcher can reload the page when the dist changes.
            command.add("-Djdesk.assets.dir="
                    + getFrontendDistDirectory().get().getAsFile().getAbsolutePath());
        }
        if (modular) {
            command.add("--module-path");
            command.add(getRuntimeClasspath().getAsPath());
            command.add("--module");
            command.add(getMainModule().get() + "/" + getMainClass().get());
        } else {
            command.add("-cp");
            command.add(getRuntimeClasspath().getAsPath());
            command.add(getMainClass().get());
        }
        getLogger().lifecycle("jdeskDev: launching {}{}", getMainClass().get(),
                devUrl == null ? "" : " with " + devUrl);
        return startProcess(command, getReloadWorkingDirectory().get().getAsFile(), "app");
    }

    private boolean runReloadCommand() throws InterruptedException {
        Process process = startProcess(getReloadCommand().get(),
                getReloadWorkingDirectory().get().getAsFile(), "reload");
        return process.waitFor() == 0;
    }

    private boolean runFrontendBuild() throws InterruptedException {
        // Static-copy mode has no build command: copy the frontend tree into dist here,
        // the same way jdeskFrontendBuild does, so the app's asset watcher reloads it.
        if (Boolean.TRUE.equals(getFrontendStaticCopy().getOrElse(false))) {
            try {
                dev.jdesk.gradle.internal.StaticCopy.copy(
                        getFrontendDirectory().get().getAsFile().toPath(),
                        getFrontendDistDirectory().get().getAsFile().toPath());
                return true;
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().warn("jdeskDev: static copy failed: {}", e.getMessage());
                return false;
            }
        }
        Process process = startProcess(getFrontendBuildCommand().get(),
                getFrontendDirectory().get().getAsFile(), "frontend-build");
        return process.waitFor() == 0;
    }

    private Process startProcess(List<String> command, File directory, String outputPrefix) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true);
        getLogger().info("jdeskDev {} command: {}", outputPrefix, command);
        getLogger().info("jdeskDev {} environment (redacted): {}", outputPrefix,
                EnvRedactor.redact(builder.environment()));
        try {
            Process process = builder.start();
            pumpOutput(process, outputPrefix);
            return process;
        } catch (IOException e) {
            throw new GradleException("jdeskDev: could not start " + outputPrefix
                    + " command " + command + " in " + directory, e);
        }
    }

    private void requireSuccessfulExit(Process process) throws InterruptedException {
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new GradleException("jdeskDev: application exited with code " + exitCode);
        }
    }

    private String awaitQuietState(String state, java.util.function.Supplier<String> stateSupplier)
            throws InterruptedException {
        long debounce = getReloadDebounceMillis().getOrElse(300);
        long lastChange = System.currentTimeMillis();
        String current = state;
        while (System.currentTimeMillis() - lastChange < debounce) {
            Thread.sleep(Math.min(POLL_MILLIS, Math.max(1L, debounce)));
            String next = stateSupplier.get();
            if (!current.equals(next)) {
                current = next;
                lastChange = System.currentTimeMillis();
            }
        }
        return current;
    }

    private String sourceState() {
        return digestOf(getReloadSources().getFiles(), "reload sources");
    }

    private String frontendSourceState() {
        return digestOf(getFrontendSources().getFiles(), "frontend sources");
    }

    private static String digestOf(Iterable<File> roots, String description) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            for (File root : roots) {
                Path path = root.toPath();
                if (Files.isDirectory(path)) {
                    try (Stream<Path> stream = Files.walk(path)) {
                        stream.filter(Files::isRegularFile).forEach(files::add);
                    }
                } else if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                updateDigest(digest, file.toAbsolutePath().normalize().toString());
                updateDigest(digest, Long.toString(Files.size(file)));
                updateDigest(digest, Files.getLastModifiedTime(file).toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new GradleException("jdeskDev: failed to inspect " + description, e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private void pumpOutput(Process process, String prefix) {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLogger().lifecycle("[{}] {}", prefix, line);
                }
            } catch (IOException ignored) {
                // Stream closes when the process exits.
            }
        }, "jdesk-" + prefix + "-output");
        pump.setDaemon(true);
        pump.start();
    }

    private void waitForDevServer(String devUrl, Process frontend) throws InterruptedException {
        int attempts = getProbeAttempts().getOrElse(60);
        int intervalMillis = getProbeIntervalMillis().getOrElse(500);
        getLogger().lifecycle("jdeskDev: waiting for dev server at {} (max {} x {} ms)",
                devUrl, attempts, intervalMillis);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!frontend.isAlive()) {
                throw new GradleException("jdeskDev: frontend exited with code "
                        + frontend.exitValue() + " before " + devUrl + " became reachable");
            }
            if (probe(devUrl)) {
                getLogger().lifecycle("jdeskDev: dev server is up after {} attempt(s)", attempt);
                return;
            }
            Thread.sleep(intervalMillis);
        }
        throw new GradleException("jdeskDev: dev server at " + devUrl
                + " did not answer within " + (attempts * intervalMillis / 1000) + "s");
    }

    private boolean probe(String devUrl) {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(devUrl).toURL().openConnection();
            connection.setConnectTimeout(400);
            connection.setReadTimeout(400);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            connection.disconnect();
            return code > 0;
        } catch (IOException | IllegalArgumentException e) {
            return false;
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
        throw new GradleException("jdeskDev: unsupported operating system " + OsSupport.osName());
    }

    private boolean hasModule(String moduleName) {
        Path[] entries = getRuntimeClasspath().getFiles().stream()
                .map(File::toPath)
                .toArray(Path[]::new);
        try {
            return ModuleFinder.of(entries).find(moduleName).isPresent();
        } catch (FindException e) {
            throw new GradleException("jdeskDev: invalid module path", e);
        }
    }

    private String existingResourcePath() {
        return getApplicationResources().getFiles().stream()
                .filter(File::isDirectory)
                .map(File::getAbsolutePath)
                .sorted()
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
}
