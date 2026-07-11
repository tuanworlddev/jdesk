package dev.jdesk.packager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Locates JDK command-line tools ({@code jdeps}, {@code jlink}, {@code jpackage},
 * {@code java}) inside a specific JDK installation. Tools are always executed from the
 * configured toolchain's home, never from whatever happens to be on {@code PATH}, so
 * builds are reproducible across machines.
 */
public final class JdkTools {
    private JdkTools() {
    }

    /**
     * Returns the path of {@code bin/<tool>} (or {@code bin/<tool>.exe} on Windows)
     * under the given JDK home.
     *
     * @throws IllegalStateException with remediation when the tool is missing
     */
    public static Path locate(Path javaHome, String tool) {
        Path found = locateOrNull(javaHome, tool);
        if (found == null) {
            throw new IllegalStateException(
                    "JDK tool '" + tool + "' not found under " + javaHome.resolve("bin")
                            + ". Configure a full JDK 25+ toolchain (a JRE or stripped"
                            + " runtime image does not ship " + tool + ").");
        }
        return found;
    }

    /** Like {@link #locate} but returns {@code null} instead of throwing. */
    public static Path locateOrNull(Path javaHome, String tool) {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(tool, "tool");
        Path bin = javaHome.resolve("bin");
        Path plain = bin.resolve(tool);
        if (Files.isRegularFile(plain)) {
            return plain;
        }
        Path exe = bin.resolve(tool + ".exe");
        if (Files.isRegularFile(exe)) {
            return exe;
        }
        return null;
    }
}
