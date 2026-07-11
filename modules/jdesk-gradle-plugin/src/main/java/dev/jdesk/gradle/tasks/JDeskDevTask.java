package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.EnvRedactor;
import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.gradle.internal.ProcessTrees;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/**
 * {@code jdeskDev}: starts the frontend dev server ({@code jdesk.frontend.devCommand})
 * as a child process, waits until {@code devUrl} answers (bounded retries), then runs
 * the application with {@code -Djdesk.dev=true -Djdesk.devUrl=<devUrl>}.
 *
 * <p>Termination: the app runs via Gradle's managed {@code javaexec} (killed on build
 * cancellation), and the dev-server process tree is destroyed in a {@code finally}
 * block plus a JVM shutdown hook, so Ctrl+C or an app failure never leaks a stale dev
 * server. All properties are {@code @Internal}: a dev session is never up-to-date.
 */
@UntrackedTask(because = "interactive development session; never up-to-date")
public abstract class JDeskDevTask extends DefaultTask {

    @Internal
    public abstract ListProperty<String> getDevCommand();

    @Internal
    public abstract DirectoryProperty getFrontendDirectory();

    @Internal
    public abstract Property<String> getDevUrl();

    @Internal
    public abstract Property<String> getMainClass();

    @Internal
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @Internal
    public abstract Property<String> getJavaExecutable();

    @Internal
    public abstract Property<Integer> getProbeAttempts();

    @Internal
    public abstract Property<Integer> getProbeIntervalMillis();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void dev() throws InterruptedException {
        if (!getMainClass().isPresent()) {
            throw new GradleException("jdeskDev: jdesk.mainClass is not set. Set it,"
                    + " e.g. jdesk { mainClass.set(\"dev.example.App\") }.");
        }
        Process frontend = null;
        Thread shutdownHook = null;
        try {
            List<String> devCommand = getDevCommand().getOrElse(List.of());
            if (!devCommand.isEmpty()) {
                if (!getFrontendDirectory().isPresent()) {
                    throw new GradleException("jdeskDev: jdesk.frontend.devCommand is set"
                            + " but jdesk.frontend.directory is not. Point it at the"
                            + " frontend project.");
                }
                File dir = getFrontendDirectory().get().getAsFile();
                ProcessBuilder builder = new ProcessBuilder(devCommand)
                        .directory(dir)
                        .redirectErrorStream(true);
                getLogger().info("jdeskDev frontend environment (redacted): {}",
                        EnvRedactor.redact(builder.environment()));
                getLogger().lifecycle("jdeskDev: starting frontend {} (in {})", devCommand, dir);
                try {
                    frontend = builder.start();
                } catch (IOException e) {
                    throw new GradleException("jdeskDev: could not start the frontend"
                            + " dev command " + devCommand + " in " + dir + ". Is the"
                            + " tool installed and jdesk.frontend.devCommand correct?", e);
                }
                Process frontendRef = frontend;
                shutdownHook = new Thread(() -> ProcessTrees.destroy(frontendRef), "jdesk-dev-cleanup");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                pumpOutput(frontend);
                String devUrl = getDevUrl().getOrNull();
                if (devUrl != null) {
                    waitForDevServer(devUrl, frontend);
                } else {
                    getLogger().warn("jdeskDev: jdesk.frontend.devUrl is not set; the app"
                            + " starts without waiting for the dev server.");
                }
            }
            String devUrl = getDevUrl().getOrNull();
            getLogger().lifecycle("jdeskDev: launching {} (jdesk.dev=true{})",
                    getMainClass().get(), devUrl == null ? "" : ", jdesk.devUrl=" + devUrl);
            getExecOperations().javaexec(spec -> {
                spec.setExecutable(getJavaExecutable().get());
                spec.getMainClass().set(getMainClass().get());
                spec.classpath(getRuntimeClasspath());
                List<String> jvmArgs = new ArrayList<>();
                // Classpath dev launch: ALL-UNNAMED. Packaged images embed the option
                // via jlink --add-options (see jdeskRuntimeImage).
                jvmArgs.add("--enable-native-access=ALL-UNNAMED");
                if (OsSupport.isMacOs()) {
                    // AppKit needs the process's first thread; the plain java launcher
                    // would otherwise run main() on a secondary thread.
                    jvmArgs.add("-XstartOnFirstThread");
                }
                spec.jvmArgs(jvmArgs);
                spec.systemProperty("jdesk.dev", "true");
                if (devUrl != null) {
                    spec.systemProperty("jdesk.devUrl", devUrl);
                }
            });
        } finally {
            if (frontend != null) {
                getLogger().lifecycle("jdeskDev: stopping frontend dev server");
                ProcessTrees.destroy(frontend);
            }
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM already shutting down
                }
            }
        }
    }

    private void pumpOutput(Process process) {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLogger().lifecycle("[frontend] {}", line);
                }
            } catch (IOException ignored) {
                // stream closes when the process dies
            }
        }, "jdesk-frontend-output");
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
                throw new GradleException("jdeskDev: the frontend dev server exited with"
                        + " code " + frontend.exitValue() + " before " + devUrl
                        + " became reachable. Check the [frontend] output above.");
            }
            if (probe(devUrl)) {
                getLogger().lifecycle("jdeskDev: dev server is up after {} attempt(s)", attempt);
                return;
            }
            Thread.sleep(intervalMillis);
        }
        throw new GradleException("jdeskDev: dev server at " + devUrl + " did not answer"
                + " within " + (attempts * intervalMillis / 1000) + "s. Check"
                + " jdesk.frontend.devUrl and the [frontend] output above.");
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
            // Any HTTP answer (including 404) proves the server is accepting connections.
            return code > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
