package dev.jdesk.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How to start a pseudo-terminal child process (see {@link ApplicationHandle#openPty}).
 *
 * @param command argv of the program to run (index 0 is the executable); must be non-empty
 * @param workingDirectory child working directory, or empty for the app's
 * @param environment extra/overriding environment variables for the child
 * @param columns initial terminal width in character cells (&gt; 0)
 * @param rows initial terminal height in character cells (&gt; 0)
 */
public record PtySpec(List<String> command, Optional<Path> workingDirectory,
        Map<String, String> environment, int columns, int rows) {

    public PtySpec {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(environment);
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("columns and rows must be > 0");
        }
    }

    /** An 80x24 PTY for {@code command} in the app's cwd with no extra environment. */
    public static PtySpec of(String... command) {
        return new PtySpec(List.of(command), Optional.empty(), Map.of(), 80, 24);
    }

    public PtySpec withSize(int columns, int rows) {
        return new PtySpec(command, workingDirectory, environment, columns, rows);
    }

    public PtySpec withWorkingDirectory(Path directory) {
        return new PtySpec(command, Optional.of(directory), environment, columns, rows);
    }

    public PtySpec withEnvironment(Map<String, String> environment) {
        return new PtySpec(command, workingDirectory, environment, columns, rows);
    }
}
