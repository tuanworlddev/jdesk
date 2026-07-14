package dev.jdesk.packager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Builds the argument list for a {@code jlink} run creating a trimmed runtime image.
 * Pure argument construction; execution is the caller's job.
 */
public final class JlinkArguments {
    private final TreeSet<String> modules;
    private final Path output;
    private final boolean noHeaderFiles;
    private final boolean noManPages;
    private final boolean stripDebug;
    private final String compress;
    private final List<String> addOptions;

    private JlinkArguments(Builder builder) {
        this.modules = new TreeSet<>(builder.modules);
        this.output = builder.output;
        this.noHeaderFiles = builder.noHeaderFiles;
        this.noManPages = builder.noManPages;
        this.stripDebug = builder.stripDebug;
        this.compress = builder.compress;
        this.addOptions = List.copyOf(builder.addOptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Arguments to pass after the {@code jlink} executable. */
    public List<String> toArguments() {
        if (modules.isEmpty()) {
            throw new IllegalStateException("jlink needs at least one module (java.base)");
        }
        Objects.requireNonNull(output, "output");
        List<String> args = new ArrayList<>();
        args.add("--add-modules");
        args.add(String.join(",", modules));
        args.add("--output");
        args.add(output.toString());
        if (noHeaderFiles) {
            args.add("--no-header-files");
        }
        if (noManPages) {
            args.add("--no-man-pages");
        }
        if (stripDebug) {
            // Drops native + class debug symbols from the image — a large, safe size win
            // for a distributed runtime (stack traces keep line numbers from the app jars).
            args.add("--strip-debug");
        }
        if (compress != null && !compress.isBlank()) {
            // JDK 21+ ZIP compression of the image resources (zip-0..zip-9).
            args.add("--compress=" + compress);
        }
        if (!addOptions.isEmpty()) {
            // jlink's add-options plugin embeds default JVM options into the image
            // (e.g. --enable-native-access=...): they apply to every launch of the
            // resulting runtime, including jpackage launchers.
            args.add("--add-options=" + String.join(" ", addOptions));
        }
        return List.copyOf(args);
    }

    public static final class Builder {
        private final TreeSet<String> modules = new TreeSet<>();
        private Path output;
        private boolean noHeaderFiles = true;
        private boolean noManPages = true;
        private boolean stripDebug = true;
        private String compress = "zip-6";
        private final List<String> addOptions = new ArrayList<>();

        private Builder() {
        }

        public Builder modules(Collection<String> moduleNames) {
            this.modules.addAll(moduleNames);
            return this;
        }

        public Builder output(Path outputDirectory) {
            this.output = outputDirectory;
            return this;
        }

        public Builder noHeaderFiles(boolean value) {
            this.noHeaderFiles = value;
            return this;
        }

        public Builder noManPages(boolean value) {
            this.noManPages = value;
            return this;
        }

        /** Strips debug symbols from the runtime image (default true). */
        public Builder stripDebug(boolean value) {
            this.stripDebug = value;
            return this;
        }

        /** ZIP compression level for image resources, {@code zip-0}..{@code zip-9} (default zip-6);
         *  null or blank disables the {@code --compress} flag. */
        public Builder compress(String level) {
            this.compress = level;
            return this;
        }

        /** Adds a default JVM option baked into the image via {@code --add-options}. */
        public Builder addOption(String jvmOption) {
            this.addOptions.add(Objects.requireNonNull(jvmOption, "jvmOption"));
            return this;
        }

        public JlinkArguments build() {
            return new JlinkArguments(this);
        }
    }
}
