package dev.jdesk.gradle;

import java.util.List;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Adds {@code -Ajdesk.ts.outputDir=<dir>} to {@code compileJava} so the jdesk-codegen
 * annotation processor emits TypeScript bindings into the frontend tree, and declares
 * that directory as a task output (correct up-to-date checks for the generated
 * {@code types.ts}/{@code commands.ts}).
 */
public abstract class TsBindingArguments implements CommandLineArgumentProvider {

    /** Absent when no frontend is configured: the option is omitted entirely. */
    @Optional
    @OutputDirectory
    public abstract DirectoryProperty getTsOutputDir();

    @Override
    public Iterable<String> asArguments() {
        if (!getTsOutputDir().isPresent()) {
            return List.of();
        }
        return List.of("-Ajdesk.ts.outputDir=" + getTsOutputDir().get().getAsFile().getAbsolutePath());
    }
}
