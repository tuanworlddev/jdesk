package dev.jdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JDeskCliTest {
    @TempDir
    Path tempDir;

    @Test
    void createsBasicTemplateWithWrapperAndResolvedTokens() throws Exception {
        Path target = tempDir.resolve("basic-app");
        Result result = run("create", target.toString(), "--name", "Desk Notes",
                "--package", "com.example.notes", "--jdesk-source", sourceRoot());

        assertThat(result.exitCode).isZero();
        assertThat(target.resolve("gradlew")).isExecutable();
        assertThat(target.resolve("gradle/wrapper/gradle-wrapper.jar")).isRegularFile();
        assertThat(target.resolve("src/main/java/com/example/notes/Main.java")).isRegularFile();
        assertThat(Files.readString(target.resolve("settings.gradle.kts")))
                .contains("includeBuild(\"" + sourceRoot().replace('\\', '/') + "\")")
                .doesNotContain("@SOURCE_");
        assertThat(Files.readString(target.resolve("build.gradle.kts")))
                .contains("id(\"dev.jdesk.application\")")
                .contains("val jdeskVersion = \"0.1.0\"")
                .contains("dev.jdesk:jdesk-runtime:$jdeskVersion")
                .doesNotContain("@PACKAGE@");
    }

    @Test
    void createsStructuredTemplateWithAllArchitectureModules() {
        Path target = tempDir.resolve("structured-app");
        Result result = run("create", target.toString(), "--template", "structured",
                "--package", "org.acme.workspace");

        assertThat(result.exitCode).isZero();
        assertThat(target.resolve("domain/src/main/java/module-info.java")).isRegularFile();
        assertThat(target.resolve("application/src/main/java/module-info.java")).isRegularFile();
        assertThat(target.resolve("infrastructure/src/main/java/module-info.java")).isRegularFile();
        assertThat(target.resolve("desktop/src/main/java/module-info.java")).isRegularFile();
        assertThat(result.out).contains(":desktop:jdeskDev");
    }

    @Test
    void createsEveryNamedFrontendTemplate() throws Exception {
        for (String template : List.of("vanilla", "react", "vue", "svelte")) {
            Path target = tempDir.resolve(template);
            Result result = run("create", target.toString(), "--template", template,
                    "--package", "org.acme." + template, "--jdesk-source", sourceRoot());
            assertThat(result.exitCode).as(template).isZero();
            assertThat(target.resolve("ui/package.json")).as(template).isRegularFile();
            assertThat(Files.readString(target.resolve("build.gradle.kts")))
                    .contains("listOf(\"npm\", \"run\", \"build\")");
        }
    }

    @Test void createsMavenTemplate() throws Exception {
        Path target = tempDir.resolve("maven-app");
        Result result = run("create", target.toString(), "--template", "maven",
                "--package", "org.acme.mavenapp");
        assertThat(result.exitCode).isZero();
        assertThat(target.resolve("pom.xml")).isRegularFile();
        assertThat(target.resolve("build.gradle.kts")).doesNotExist();
    }

    @Test
    void refusesNonEmptyTargetAndInvalidPackage() throws Exception {
        Path target = tempDir.resolve("existing");
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "user data");

        Result existing = run("create", target.toString());
        Result invalid = run("create", tempDir.resolve("bad").toString(),
                "--package", "Not A Package");

        assertThat(existing.exitCode).isEqualTo(2);
        assertThat(existing.err).contains("Target directory is not empty");
        assertThat(Files.readString(target.resolve("keep.txt"))).isEqualTo("user data");
        assertThat(invalid.exitCode).isEqualTo(2);
        assertThat(invalid.err).contains("Invalid Java package");
    }

    @Test
    void printsHelpAndRejectsUnknownCommands() {
        Result help = run("--help");
        Result unknown = run("generate");

        assertThat(help.exitCode).isZero();
        assertThat(help.out).contains("create <directory>", "vanilla|react|vue|svelte",
                "build", "bundle");
        assertThat(unknown.exitCode).isEqualTo(2);
        assertThat(unknown.err).contains("Unknown command: generate");
    }

    private static String sourceRoot() {
        return System.getProperty("jdesk.source.root");
    }

    private static Result run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = JDeskCli.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String out, String err) {
    }
}
