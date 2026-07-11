package dev.jdesk.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;

/** Shared helpers building real, isolated consumer builds for TestKit runs. */
final class FunctionalTestSupport {
    private FunctionalTestSupport() {
    }

    /** Framework jars built by this repository, passed in by the test task. */
    static List<String> frameworkClasspath() {
        String value = System.getProperty("jdesk.test.framework.classpath", "");
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** Groovy-DSL {@code files(...)} literal for the framework jars. */
    static String frameworkFilesLiteral() {
        List<String> quoted = frameworkClasspath().stream()
                .map(p -> "'" + p.replace('\\', '/') + "'")
                .toList();
        return "files(" + String.join(", ", quoted) + ")";
    }

    /** Path of the JVM running the tests (a JDK 25); portable across OSes. */
    static String javaExecutable() {
        Path home = Path.of(System.getProperty("java.home"));
        Path exe = home.resolve("bin/java.exe");
        if (Files.isRegularFile(exe)) {
            return exe.toString();
        }
        return home.resolve("bin/java").toString();
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static void writeSettings(Path projectDir) throws IOException {
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
    }

    /** A tiny portable "frontend build tool": java single-file launcher writing dist/. */
    static void writeFrontend(Path projectDir) throws IOException {
        write(projectDir.resolve("ui/Build.java"), """
                import java.nio.file.Files;
                import java.nio.file.Path;

                public class Build {
                    public static void main(String[] args) throws Exception {
                        Path dist = Path.of("dist");
                        Files.createDirectories(dist);
                        Files.writeString(dist.resolve("app.js"), "console.log('built');\\n");
                        Files.writeString(dist.resolve("index.html"), "<!doctype html>\\n");
                    }
                }
                """);
        write(projectDir.resolve("ui/index.src.html"), "<!-- source marker -->\n");
    }

    /** Frontend block whose buildCommand runs {@link #writeFrontend}'s Build.java. */
    static String frontendBlock() {
        return """
                    frontend {
                        directory = layout.projectDirectory.dir('ui')
                        devCommand = ['npm', 'run', 'dev']
                        buildCommand = ['%s', 'Build.java']
                        devUrl = 'http://127.0.0.1:5173'
                        distDirectory = layout.projectDirectory.dir('ui/dist')
                    }
                """.formatted(javaExecutable().replace('\\', '/'));
    }

    static GradleRunner runner(Path projectDir, String... arguments) {
        List<String> args = new ArrayList<>(Arrays.asList(arguments));
        args.add("--stacktrace");
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(args);
    }
}
