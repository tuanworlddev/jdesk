package dev.jdesk.packager;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the argument list for a {@code jdeps --print-module-deps} run used to compute
 * the JDK module set required by a classpath application. Pure argument construction;
 * execution is the caller's job (Gradle {@code ExecOperations} in the plugin).
 */
public final class JdepsArguments {
    private final boolean printModuleDeps;
    private final boolean ignoreMissingDeps;
    private final int multiRelease;
    private final List<Path> classPath;
    private final List<Path> roots;

    private JdepsArguments(Builder builder) {
        this.printModuleDeps = builder.printModuleDeps;
        this.ignoreMissingDeps = builder.ignoreMissingDeps;
        this.multiRelease = builder.multiRelease;
        this.classPath = List.copyOf(builder.classPath);
        this.roots = List.copyOf(builder.roots);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Arguments to pass after the {@code jdeps} executable. */
    public List<String> toArguments() {
        if (roots.isEmpty()) {
            throw new IllegalStateException("jdeps needs at least one root jar/directory");
        }
        List<String> args = new ArrayList<>();
        if (printModuleDeps) {
            args.add("--print-module-deps");
        }
        if (ignoreMissingDeps) {
            args.add("--ignore-missing-deps");
        }
        if (multiRelease > 0) {
            args.add("--multi-release");
            args.add(Integer.toString(multiRelease));
        }
        if (!classPath.isEmpty()) {
            args.add("--class-path");
            args.add(classPath.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        for (Path root : roots) {
            args.add(root.toString());
        }
        return List.copyOf(args);
    }

    public static final class Builder {
        private boolean printModuleDeps = true;
        private boolean ignoreMissingDeps = true;
        private int multiRelease;
        private final List<Path> classPath = new ArrayList<>();
        private final List<Path> roots = new ArrayList<>();

        private Builder() {
        }

        public Builder printModuleDeps(boolean value) {
            this.printModuleDeps = value;
            return this;
        }

        public Builder ignoreMissingDeps(boolean value) {
            this.ignoreMissingDeps = value;
            return this;
        }

        /** Sets {@code --multi-release}; 0 omits the flag. */
        public Builder multiRelease(int featureVersion) {
            this.multiRelease = featureVersion;
            return this;
        }

        public Builder classPath(List<Path> entries) {
            this.classPath.addAll(entries);
            return this;
        }

        public Builder roots(List<Path> rootJarsOrDirs) {
            this.roots.addAll(rootJarsOrDirs);
            return this;
        }

        public JdepsArguments build() {
            return new JdepsArguments(this);
        }
    }
}
