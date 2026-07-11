package dev.jdesk.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Command-line project generator for JDesk applications. */
public final class JDeskCli {
    private static final String DEFAULT_VERSION = "0.1.1";
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)+");
    private static final Set<String> TEMPLATES =
            Set.of("basic", "structured", "vanilla", "react", "vue", "svelte", "maven");
    private static final List<String> WRAPPER_FILES = List.of(
            "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties");

    private JDeskCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Runs the CLI without terminating the hosting JVM. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || isHelp(args[0])) {
                usage(out);
                return 0;
            }
            if ("build".equals(args[0]) || "bundle".equals(args[0])) {
                if (args.length != 1) {
                    throw new CliException(args[0] + " does not accept arguments");
                }
                return runGradle(args[0], out, err);
            }
            if (!"create".equals(args[0])) {
                throw new CliException("Unknown command: " + args[0]);
            }
            CreateOptions options = parseCreate(args);
            create(options);
            out.println("Created JDesk " + options.template + " app at "
                    + options.target.toAbsolutePath().normalize());
            if ("maven".equals(options.template)) {
                out.println("Next: cd " + options.target);
                out.println("  # Pre-alpha: JDesk is not on Maven Central yet. Install it");
                out.println("  # into your local repository first, from a JDesk checkout:");
                out.println("  #   ./gradlew publishToMavenLocal");
                out.println("  (cd ui && java Build.java)   # build the UI");
                out.println("  mvn compile                 # resolve deps, generate bindings");
                out.println("  mvn exec:exec               # run the app");
            } else {
                out.println("Next: cd " + options.target + " && ./gradlew run");
                out.println("Development: ./gradlew " + options.devTask() + "jdeskDev");
            }
            return 0;
        } catch (CliException e) {
            err.println("jdesk: " + e.getMessage());
            err.println("Run 'jdesk --help' for usage.");
            return 2;
        } catch (IOException e) {
            err.println("jdesk: " + e.getMessage());
            return 1;
        }
    }

    private static CreateOptions parseCreate(String[] args) {
        Path target = null;
        String template = "basic";
        String packageName = null;
        String name = null;
        String version = DEFAULT_VERSION;
        Path source = null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--template" -> template = requireValue(args, ++i, arg);
                case "--package" -> packageName = requireValue(args, ++i, arg);
                case "--name" -> name = requireValue(args, ++i, arg);
                case "--jdesk-version" -> version = requireValue(args, ++i, arg);
                case "--jdesk-source" -> source = Path.of(requireValue(args, ++i, arg));
                case "--force" -> force = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new CliException("Unknown option: " + arg);
                    }
                    if (target != null) {
                        throw new CliException("Only one target directory may be specified");
                    }
                    target = Path.of(arg);
                }
            }
        }
        if (target == null) {
            throw new CliException("create requires a target directory");
        }
        if (!TEMPLATES.contains(template)) {
            throw new CliException("Unsupported template '" + template
                    + "'; expected basic, structured, vanilla, react, vue, svelte, or maven");
        }
        String projectName = name == null ? target.toAbsolutePath().getFileName().toString() : name;
        if (projectName.isBlank() || projectName.contains("/") || projectName.contains("\\")) {
            throw new CliException("Application name must be non-empty and may not contain slashes");
        }
        String pkg = packageName == null ? defaultPackage(projectName) : packageName;
        if (!PACKAGE_NAME.matcher(pkg).matches()) {
            throw new CliException("Invalid Java package '" + pkg
                    + "'; use lowercase reverse-DNS form such as com.example.app");
        }
        if (version.isBlank()) {
            throw new CliException("JDesk version must not be blank");
        }
        if (source != null) {
            source = source.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source.resolve("settings.gradle.kts"))) {
                throw new CliException("--jdesk-source is not a JDesk checkout: " + source);
            }
        }
        return new CreateOptions(target, template, pkg, projectName, version, source, force);
    }

    private static void create(CreateOptions options) throws IOException {
        Path target = options.target.toAbsolutePath().normalize();
        if (Files.exists(target) && !options.force) {
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new CliException("Target directory is not empty: " + target
                            + " (use --force to overwrite template files)");
                }
            }
        }
        Files.createDirectories(target);
        Map<String, String> tokens = tokens(options);
        for (TemplateCatalog.TemplateFile file : TemplateCatalog.files(options.template)) {
            String path = file.output();
            String outputPath = "gitignore".equals(path) ? ".gitignore" : replace(path, tokens);
            Path output = target.resolve(outputPath).normalize();
            if (!output.startsWith(target)) {
                throw new CliException("Template attempted to write outside the target: " + path);
            }
            Files.createDirectories(output.getParent());
            Files.writeString(output, replace(readResource("templates/" + file.resource()), tokens),
                    StandardCharsets.UTF_8);
        }
        // Maven projects use `mvn`, not the Gradle wrapper.
        if (!"maven".equals(options.template)) {
            copyWrapper(target);
        }
    }

    private static Map<String, String> tokens(CreateOptions options) {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("@PROJECT_NAME@", options.name);
        tokens.put("@PACKAGE@", options.packageName);
        tokens.put("@PACKAGE_PATH@", options.packageName.replace('.', '/'));
        tokens.put("@APP_ID@", options.packageName);
        tokens.put("@JDESK_VERSION@", options.version);
        tokens.put("@PLUGIN_VERSION@", options.source == null
                ? " version \"" + options.version + "\""
                : "");
        tokens.put("@SOURCE_INCLUDE@", options.source == null
                ? ""
                : "    includeBuild(\"" + gradlePath(options.source) + "\")\n");
        tokens.put("@SOURCE_BUILD_INCLUDE@", options.source == null
                ? ""
                : "includeBuild(\"" + gradlePath(options.source) + "\")\n");
        return tokens;
    }

    private static void copyWrapper(Path target) throws IOException {
        for (String file : WRAPPER_FILES) {
            String resource = "dev/jdesk/cli/wrapper/" + file;
            Path output = target.resolve(file);
            Files.createDirectories(output.getParent());
            try (InputStream input = JDeskCli.class.getModule().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("CLI distribution is missing bundled resource " + resource);
                }
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        target.resolve("gradlew").toFile().setExecutable(true, false);
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream input = JDeskCli.class.getModule().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing template resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String replace(String value, Map<String, String> tokens) {
        String result = value;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            result = result.replace(token.getKey(), token.getValue());
        }
        return result;
    }

    private static String defaultPackage(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        if (slug.isBlank() || Character.isDigit(slug.charAt(0))) {
            slug = "app" + slug;
        }
        return "com.example." + slug;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new CliException(option + " requires a value");
        }
        return args[index];
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value) || "help".equals(value);
    }

    private static String gradlePath(Path path) {
        return path.toString().replace('\\', '/').replace("\"", "\\\"");
    }

    private static void usage(PrintStream out) {
        out.println("Usage: jdesk <command> [options]");
        out.println("  create <directory>            Create a JDesk application");
        out.println("  build                         Build and test the current application");
        out.println("  bundle                        Build the native installer for this OS");
        out.println("  --template basic|structured|vanilla|react|vue|svelte|maven");
        out.println("  --package <java.package>      Reverse-DNS package/application id");
        out.println("  --name <display-name>         Application and Gradle project name");
        out.println("  --jdesk-version <version>     Framework version");
        out.println("  --jdesk-source <directory>    Use a local JDesk composite build");
        out.println("  --force                       Overwrite generated files");
    }

    private static int runGradle(String command, PrintStream out, PrintStream err)
            throws IOException {
        Path wrapper = Path.of(System.getProperty("user.dir"), isWindows() ? "gradlew.bat" : "gradlew");
        if (!Files.isRegularFile(wrapper)) {
            throw new CliException("No Gradle wrapper found in " + Path.of("").toAbsolutePath()
                    + "; run this command from a generated JDesk project");
        }
        List<String> process = new ArrayList<>();
        if (isWindows()) {
            process.add("cmd.exe");
            process.add("/c");
        }
        process.add(wrapper.toAbsolutePath().toString());
        process.add("build".equals(command) ? "build" : "jdeskInstaller");
        out.println("jdesk " + command + ": " + String.join(" ", process));
        Process child = new ProcessBuilder(process).inheritIO().start();
        try {
            return child.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroy();
            err.println("jdesk: interrupted while running Gradle");
            return 130;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    private record CreateOptions(Path target, String template, String packageName,
                                 String name, String version, Path source, boolean force) {
        String devTask() {
            return "structured".equals(template) ? ":desktop:" : "";
        }
    }

    private static final class CliException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CliException(String message) {
            super(message);
        }
    }
}
