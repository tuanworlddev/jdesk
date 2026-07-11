package dev.jdesk.packager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the argument list for a {@code jpackage --type app-image} run in classpath or
 * named-module mode. Installer construction has a separate argument builder. Pure
 * argument construction; execution is the caller's job.
 */
public final class JpackageArguments {
    private final String name;
    private final Path input;
    private final String mainJar;
    private final String mainClass;
    private final Path modulePath;
    private final String module;
    private final Path runtimeImage;
    private final Path destination;
    private final String appVersion;
    private final List<String> javaOptions;
    private final String macPackageIdentifier;

    private JpackageArguments(Builder builder) {
        this.name = require(builder.name, "name");
        this.input = builder.input;
        this.mainJar = builder.mainJar;
        this.mainClass = builder.mainClass;
        this.modulePath = builder.modulePath;
        this.module = builder.module;
        boolean classpathMode = input != null || mainJar != null || mainClass != null;
        boolean moduleMode = modulePath != null || module != null;
        if (classpathMode == moduleMode) {
            throw new IllegalStateException(
                    "jpackage requires exactly one launch mode: classpath or module");
        }
        if (classpathMode) {
            Objects.requireNonNull(input, "input");
            require(mainJar, "mainJar");
            require(mainClass, "mainClass");
        } else {
            Objects.requireNonNull(modulePath, "modulePath");
            require(module, "module");
        }
        this.runtimeImage = Objects.requireNonNull(builder.runtimeImage, "runtimeImage");
        this.destination = Objects.requireNonNull(builder.destination, "destination");
        this.appVersion = require(builder.appVersion, "appVersion");
        this.javaOptions = List.copyOf(builder.javaOptions);
        this.macPackageIdentifier = builder.macPackageIdentifier;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("jpackage requires " + what);
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Normalizes a build version into something jpackage accepts. Pre-release suffixes
     * (e.g. {@code -SNAPSHOT}) are stripped. macOS additionally rejects a leading zero
     * major version (CFBundleVersion rules), in which case {@code 1.0.0} is used.
     */
    public static String normalizeVersion(String rawVersion, boolean macOs) {
        String version = rawVersion == null ? "" : rawVersion.strip();
        int dash = version.indexOf('-');
        if (dash >= 0) {
            version = version.substring(0, dash);
        }
        if (!version.matches("\\d+(\\.\\d+)*")) {
            return "1.0.0";
        }
        if (macOs) {
            String major = version.split("\\.")[0];
            if (Long.parseLong(major) == 0) {
                return "1.0.0";
            }
        }
        return version;
    }

    /** Arguments to pass after the {@code jpackage} executable. */
    public List<String> toArguments() {
        List<String> args = new ArrayList<>(List.of(
                "--type", "app-image", "--name", name));
        if (module != null) {
            args.addAll(List.of("--module-path", modulePath.toString(), "--module", module));
        } else {
            args.addAll(List.of("--input", input.toString(), "--main-jar", mainJar,
                    "--main-class", mainClass));
        }
        args.addAll(List.of(
                "--runtime-image", runtimeImage.toString(),
                "--dest", destination.toString(),
                "--app-version", appVersion));
        for (String option : javaOptions) {
            args.add("--java-options");
            args.add(option);
        }
        if (macPackageIdentifier != null && !macPackageIdentifier.isBlank()) {
            args.add("--mac-package-identifier");
            args.add(macPackageIdentifier);
        }
        return List.copyOf(args);
    }

    public static final class Builder {
        private String name;
        private Path input;
        private String mainJar;
        private String mainClass;
        private Path modulePath;
        private String module;
        private Path runtimeImage;
        private Path destination;
        private String appVersion;
        private final List<String> javaOptions = new ArrayList<>();
        private String macPackageIdentifier;

        private Builder() {
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder input(Path stagingDirectory) {
            this.input = stagingDirectory;
            return this;
        }

        public Builder mainJar(String jarFileName) {
            this.mainJar = jarFileName;
            return this;
        }

        public Builder mainClass(String value) {
            this.mainClass = value;
            return this;
        }

        public Builder modulePath(Path value) {
            this.modulePath = value;
            return this;
        }

        public Builder module(String moduleName, String mainClassName) {
            this.module = require(moduleName, "module") + "/" + require(mainClassName, "mainClass");
            return this;
        }

        public Builder runtimeImage(Path value) {
            this.runtimeImage = value;
            return this;
        }

        public Builder destination(Path value) {
            this.destination = value;
            return this;
        }

        public Builder appVersion(String value) {
            this.appVersion = value;
            return this;
        }

        /** Adds a JVM option passed to the packaged launcher via {@code --java-options}. */
        public Builder javaOption(String jvmOption) {
            this.javaOptions.add(Objects.requireNonNull(jvmOption, "jvmOption"));
            return this;
        }

        /** macOS bundle identifier ({@code --mac-package-identifier}); ignored elsewhere. */
        public Builder macPackageIdentifier(String value) {
            this.macPackageIdentifier = value;
            return this;
        }

        public JpackageArguments build() {
            return new JpackageArguments(this);
        }
    }
}
