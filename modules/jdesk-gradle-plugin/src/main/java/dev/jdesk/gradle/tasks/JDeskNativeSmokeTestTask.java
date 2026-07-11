package dev.jdesk.gradle.tasks;

import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.gradle.internal.ProcessTrees;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * {@code jdeskNativeSmokeTest}: launches the packaged application image's real launcher
 * with a {@code --jdesk-smoke} passthrough argument and requires exit code 0 within a
 * timeout. The application is expected to run a genuine self-check when it sees the
 * flag (start, probe, exit 0). This task never fakes a pass: no launcher, non-zero exit
 * or a timeout each fail the build.
 */
@UntrackedTask(because = "launches the packaged native application; must run every time")
public abstract class JDeskNativeSmokeTestTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getPackageDirectory();

    @Internal
    public abstract Property<String> getImageName();

    @Internal
    public abstract Property<Long> getTimeoutSeconds();

    @Internal
    public abstract ListProperty<String> getSmokeArgs();

    @Internal
    public abstract RegularFileProperty getLogFile();

    @TaskAction
    public void smokeTest() throws IOException, InterruptedException {
        Path launcher = locateLauncher();
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(getSmokeArgs().getOrElse(List.of("--jdesk-smoke")));

        File logFile = getLogFile().get().getAsFile();
        Files.createDirectories(logFile.getParentFile().toPath());
        getLogger().lifecycle("jdeskNativeSmokeTest: {} (log: {})", command, logFile);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start();
        long timeout = getTimeoutSeconds().getOrElse(180L);
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                ProcessTrees.destroy(process);
                throw new GradleException("jdeskNativeSmokeTest: the packaged app did"
                        + " not exit within " + timeout + "s. A smoke run must perform"
                        + " its self-check and exit 0. Log: " + logFile + "\n"
                        + tail(logFile));
            }
        } catch (InterruptedException e) {
            ProcessTrees.destroy(process);
            throw e;
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new GradleException("jdeskNativeSmokeTest: the packaged app exited"
                    + " with code " + exit + " (expected 0). Log: " + logFile + "\n"
                    + tail(logFile));
        }
        getLogger().lifecycle("jdeskNativeSmokeTest: packaged app exited 0 within {}s", timeout);
    }

    private Path locateLauncher() {
        Path dest = getPackageDirectory().get().getAsFile().toPath();
        String name = getImageName().get();
        Path launcher;
        if (OsSupport.isMacOs()) {
            launcher = dest.resolve(name + ".app/Contents/MacOS/" + name);
        } else if (OsSupport.isWindows()) {
            launcher = dest.resolve(name).resolve(name + ".exe");
        } else {
            launcher = dest.resolve(name).resolve("bin").resolve(name);
        }
        if (!Files.isRegularFile(launcher)) {
            throw new GradleException("jdeskNativeSmokeTest: no app-image launcher at "
                    + launcher + ". Run jdeskPackage first (this task normally depends"
                    + " on it) and check its output.");
        }
        return launcher;
    }

    private static String tail(File logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile.toPath());
            int from = Math.max(0, lines.size() - 30);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "(log unreadable: " + e + ")";
        }
    }
}
