package dev.jdesk.gradle;

import static dev.jdesk.gradle.FunctionalTestSupport.frameworkFilesLiteral;
import static dev.jdesk.gradle.FunctionalTestSupport.frontendBlock;
import static dev.jdesk.gradle.FunctionalTestSupport.runner;
import static dev.jdesk.gradle.FunctionalTestSupport.write;
import static dev.jdesk.gradle.FunctionalTestSupport.writeFrontend;
import static dev.jdesk.gradle.FunctionalTestSupport.writeSettings;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real Gradle TestKit runs (no mocked Gradle): each test creates an isolated consumer
 * build in a temp directory, applies {@code dev.jdesk.application} via the plugin
 * classpath, and executes real tasks.
 */
class JDeskApplicationPluginFunctionalTest {

    @TempDir
    Path tempDir;

    // (a) extension registration + task listing, exact spec section 14 shape (Kotlin DSL)
    @Test
    void registersExtensionAndAllTasksWithSpecShape() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        write(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        write(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("dev.jdesk.application")
                }

                jdesk {
                    applicationId.set("dev.example.app")
                    mainClass.set("dev.example.App")

                    frontend {
                        directory.set(layout.projectDirectory.dir("ui"))
                        devCommand.set(listOf("npm", "run", "dev"))
                        buildCommand.set(listOf("npm", "run", "build"))
                        devUrl.set("http://127.0.0.1:5173")
                        distDirectory.set(layout.projectDirectory.dir("ui/dist"))
                    }
                }
                """);

        BuildResult result = runner(projectDir, "tasks", "--group", "jdesk").build();

        assertThat(result.getOutput()).contains(
                "jdeskDoctor",
                "jdeskGenerateBindings",
                "jdeskFrontendBuild",
                "jdeskDev",
                "jdeskRuntimeImage",
                "jdeskPackage",
                "jdeskInstaller",
                "jdeskNativeSmokeTest",
                "jdeskVerifyEvidence");
    }

    // (b) doctor failure modes: every problem listed, with remediation
    @Test
    void doctorListsEveryProblemWithRemediation() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                jdesk {
                    applicationId = 'Not A Reverse DNS Id'
                    // mainClass deliberately unset
                    frontend {
                        directory = layout.projectDirectory.dir('ui')
                        buildCommand = ['definitely-not-a-real-tool-8271', 'build']
                        distDirectory = layout.projectDirectory.dir('ui/dist')
                    }
                }
                """);

        BuildResult result = runner(projectDir, "jdeskDoctor").buildAndFail();

        assertThat(result.getOutput())
                .contains("3 problem(s)")
                .contains("Not A Reverse DNS Id")
                .contains("reverse-DNS")
                .contains("jdesk.mainClass is not set")
                .contains("definitely-not-a-real-tool-8271")
                .contains("Install it or adjust jdesk.frontend.buildCommand");
    }

    // (b) doctor success path
    @Test
    void doctorPassesOnValidConfiguration() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                }
                """);

        BuildResult result = runner(projectDir, "jdeskDoctor").build();

        assertThat(result.task(":jdeskDoctor").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput())
                .contains("jdeskDoctor: environment OK")
                .contains("JDK toolchain")
                .contains("REPORT   OS");
    }

    // (c) frontend build executes a real portable command and honors up-to-date checks
    @Test
    void frontendBuildRunsAndIsUpToDateOnSecondRun() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        writeFrontend(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                // Isolated consumer without repositories: override the default
                // published jdesk-codegen coordinates with an empty processor path.
                dependencies { jdeskCodegen files() }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                %s}
                """.formatted(frontendBlock()));

        BuildResult first = runner(projectDir, "jdeskFrontendBuild").build();
        assertThat(first.task(":jdeskFrontendBuild").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(projectDir.resolve("ui/dist/app.js")).exists();

        BuildResult second = runner(projectDir, "jdeskFrontendBuild").build();
        assertThat(second.task(":jdeskFrontendBuild").getOutcome())
                .as("unchanged inputs must be UP-TO-DATE (dist output excluded from inputs)")
                .isEqualTo(TaskOutcome.UP_TO_DATE);

        // Changing a frontend source re-triggers the build.
        write(projectDir.resolve("ui/index.src.html"), "<!-- changed -->\n");
        BuildResult third = runner(projectDir, "jdeskFrontendBuild").build();
        assertThat(third.task(":jdeskFrontendBuild").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void frontendBuildSkipsWithNoSourceWhenNoFrontendConfigured() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                }
                """);

        BuildResult result = runner(projectDir, "jdeskFrontendBuild").build();

        assertThat(result.task(":jdeskFrontendBuild").getOutcome())
                .isEqualTo(TaskOutcome.NO_SOURCE);
    }

    // (d) configuration-cache compatibility of doctor + frontendBuild
    @Test
    void doctorAndFrontendBuildReuseTheConfigurationCache() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        writeFrontend(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                // Isolated consumer without repositories: override the default
                // published jdesk-codegen coordinates with an empty processor path.
                dependencies { jdeskCodegen files() }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                %s}
                """.formatted(frontendBlock()));

        BuildResult first = runner(projectDir,
                "--configuration-cache", "jdeskDoctor", "jdeskFrontendBuild").build();
        assertThat(first.getOutput()).contains("Configuration cache entry stored");

        BuildResult second = runner(projectDir,
                "--configuration-cache", "jdeskDoctor", "jdeskFrontendBuild").build();
        assertThat(second.getOutput()).contains("Reusing configuration cache");
        assertThat(second.task(":jdeskDoctor").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    // (e) paths with spaces and non-ASCII characters
    @Test
    void worksInProjectDirectoryWithSpacesAndNonAscii() throws IOException {
        Path projectDir = tempDir.resolve("consumer app ünicøde");
        writeSettings(projectDir);
        writeFrontend(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                // Isolated consumer without repositories: override the default
                // published jdesk-codegen coordinates with an empty processor path.
                dependencies { jdeskCodegen files() }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                %s}
                """.formatted(frontendBlock()));

        BuildResult result = runner(projectDir, "jdeskDoctor", "jdeskFrontendBuild").build();

        assertThat(result.task(":jdeskDoctor").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":jdeskFrontendBuild").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(projectDir.resolve("ui/dist/app.js")).exists();
    }

    @Test
    void installerFailsHonestlyUntilPhase7() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                }
                """);

        BuildResult result = runner(projectDir, "jdeskInstaller").buildAndFail();

        assertThat(result.getOutput()).contains("Phase 7");
    }

    // jdeskGenerateBindings: real jdesk-codegen run producing TypeScript into the ui tree
    @Test
    void generateBindingsRunsCodegenAndWritesTypeScript() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        writeFrontend(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                def frameworkJars = %s
                dependencies {
                    implementation frameworkJars
                    jdeskCodegen frameworkJars
                }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'com.example.app.App'
                %s}
                """.formatted(frameworkFilesLiteral(), frontendBlock()));
        write(projectDir.resolve("src/main/java/com/example/app/GreetingService.java"), """
                package com.example.app;

                import dev.jdesk.api.DesktopCommand;
                import dev.jdesk.api.InvocationContext;
                import dev.jdesk.api.RequiresCapability;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;

                public class GreetingService {
                    public record GreetRequest(String name) {
                    }

                    public record GreetResponse(String message) {
                    }

                    @DesktopCommand("greeting.greet")
                    @RequiresCapability("greeting:use")
                    public CompletionStage<GreetResponse> greet(
                            GreetRequest request, InvocationContext context) {
                        return CompletableFuture.completedFuture(
                                new GreetResponse("Hello, " + request.name()));
                    }
                }
                """);

        BuildResult result = runner(projectDir, "jdeskGenerateBindings").build();

        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":jdeskGenerateBindings").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        Path tsDir = projectDir.resolve("ui/src/generated");
        assertThat(tsDir.resolve("commands.ts")).exists();
        assertThat(tsDir.resolve("types.ts")).exists();
        assertThat(Files.readString(tsDir.resolve("commands.ts"))).contains("greeting");
        Path generatedRegistry = projectDir.resolve(
                "build/classes/java/main/com/example/app/GreetingServiceCommands.class");
        assertThat(generatedRegistry).exists();
    }

    // jdeskRuntimeImage: real jdeps + jlink run producing a bootable runtime image
    @Test
    void runtimeImageProducesTrimmedJdkImage() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                dependencies {
                    jdeskCodegen files() // no annotation processing in this consumer
                }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'com.example.app.App'
                }
                """);
        write(projectDir.resolve("src/main/java/com/example/app/App.java"), """
                package com.example.app;

                public final class App {
                    private App() {
                    }

                    public static void main(String[] args) {
                        System.out.println("hello " + java.net.http.HttpClient.Version.HTTP_2);
                    }
                }
                """);

        BuildResult result = runner(projectDir, "jdeskRuntimeImage").build();

        assertThat(result.task(":jdeskRuntimeImage").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput())
                .contains("JDK modules = ")
                .contains("java.base")
                .contains("--enable-native-access=ALL-UNNAMED");
        Path image = projectDir.resolve("build/jdesk/runtime-image");
        boolean launcherExists = Files.isRegularFile(image.resolve("bin/java"))
                || Files.isRegularFile(image.resolve("bin/java.exe"));
        assertThat(launcherExists).as("runtime image ships bin/java").isTrue();
        assertThat(image.resolve("release")).exists();
    }

    // jdeskVerifyEvidence: real VerifyMain run failing on an empty evidence directory
    @Test
    void verifyEvidenceFailsWhenNoEvidenceRunsExist() throws IOException {
        Path projectDir = tempDir.resolve("consumer");
        writeSettings(projectDir);
        Files.createDirectories(projectDir.resolve("build/evidence"));
        write(projectDir.resolve("build.gradle"), """
                plugins { id 'dev.jdesk.application' }
                dependencies {
                    jdeskTestkit %s
                }
                jdesk {
                    applicationId = 'dev.example.app'
                    mainClass = 'dev.example.App'
                }
                """.formatted(frameworkFilesLiteral()));

        BuildResult result = runner(projectDir, "jdeskVerifyEvidence").buildAndFail();

        assertThat(result.getOutput()).contains("no evidence runs found");
    }
}
