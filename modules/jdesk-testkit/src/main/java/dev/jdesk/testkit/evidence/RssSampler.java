package dev.jdesk.testkit.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the current process RSS via OS facilities (spec 17.5 baseline recording).
 * Returns -1 when the platform offers no supported probe; callers record the value,
 * they never fail on it (baselines precede thresholds).
 */
public final class RssSampler {
    private RssSampler() {
    }

    public static long currentRssBytes() {
        long pid = ProcessHandle.current().pid();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        try {
            if (os.contains("linux")) {
                for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith("VmRSS:")) {
                        return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                    }
                }
                return -1;
            }
            if (os.contains("mac")) {
                return runAndParse(List.of("ps", "-o", "rss=", "-p", Long.toString(pid))) * 1024;
            }
            if (os.contains("win")) {
                return runAndParse(List.of("powershell", "-NoProfile", "-Command",
                        "(Get-Process -Id " + pid + ").WorkingSet64"));
            }
        } catch (IOException | NumberFormatException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        return -1;
    }

    private static long runAndParse(List<String> command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).strip();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return Long.parseLong(output.replaceAll("[^0-9]", ""));
    }
}
