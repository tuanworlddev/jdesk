package dev.jdesk.gradle;

import dev.jdesk.gradle.internal.PluginVersion;
import dev.jdesk.gradle.internal.OsSupport;
import dev.jdesk.gradle.tasks.JDeskDevTask;
import dev.jdesk.gradle.tasks.JDeskDoctorTask;
import dev.jdesk.gradle.tasks.JDeskFrontendBuildTask;
import dev.jdesk.gradle.tasks.JDeskNativeSmokeTestTask;
import dev.jdesk.gradle.tasks.JDeskInstallerTask;
import dev.jdesk.gradle.tasks.JDeskPackageTask;
import dev.jdesk.gradle.tasks.JDeskRuntimeImageTask;
import dev.jdesk.gradle.tasks.JDeskVerifyEvidenceTask;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * The {@code dev.jdesk.application} plugin (spec section 14). Registers the
 * {@code jdesk} extension and the jdesk task group: doctor, generateBindings,
 * frontendBuild, dev, runtimeImage, package, installer,
 * nativeSmokeTest and verifyEvidence. All tasks use lazy configuration; everything
 * except explicitly untracked diagnostics is configuration-cache compatible.
 */
public class JDeskApplicationPlugin implements Plugin<Project> {
    public static final String GROUP = "jdesk";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");

        ObjectFactory objects = project.getObjects();
        ProjectLayout layout = project.getLayout();
        TaskContainer tasks = project.getTasks();

        JDeskExtension extension = project.getExtensions().create("jdesk", JDeskExtension.class);
        // mainModule is optional: set it (with a module-info.java) for a named-module
        // app, or leave it unset for a simpler classpath app. Tasks branch on presence.
        JDeskFrontendExtension frontend = extension.getFrontend();
        JDeskDevelopmentExtension development = extension.getDevelopment();
        frontend.getDistDirectory().convention(frontend.getDirectory().dir("dist"));
        frontend.getTsOutputDir().convention(frontend.getDirectory().dir("src/generated"));
        development.getJavaReload().convention(true);
        development.getReloadDebounceMillis().convention(300);
        development.getReloadCommand().convention(
                project.getProviders().provider(() -> defaultReloadCommand(project)));

        // Dependency buckets defaulting to the published framework artifacts. Builds
        // with the artifacts in a repository need no extra configuration; composite or
        // TestKit builds override them (e.g. jdeskCodegen files(...) / project(...)).
        DependencyHandler dependencies = project.getDependencies();
        Configuration jdeskCodegen = project.getConfigurations().create("jdeskCodegen", c -> {
            c.setCanBeConsumed(false);
            c.setDescription("JDesk annotation processor used by jdeskGenerateBindings"
                    + " (defaults to dev.jdesk:jdesk-codegen).");
            c.defaultDependencies(deps -> deps.add(
                    dependencies.create("dev.jdesk:jdesk-codegen:" + PluginVersion.get())));
        });
        project.getConfigurations()
                .named(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
                        c -> c.extendsFrom(jdeskCodegen));
        Configuration jdeskTestkit = project.getConfigurations().create("jdeskTestkit", c -> {
            c.setCanBeConsumed(false);
            c.setDescription("Classpath of the JDesk evidence verifier used by"
                    + " jdeskVerifyEvidence (defaults to dev.jdesk:jdesk-testkit).");
            c.defaultDependencies(deps -> deps.add(
                    dependencies.create("dev.jdesk:jdesk-testkit:" + PluginVersion.get())));
        });

        // Toolchain-derived providers (lazy, configuration-cache safe).
        JavaToolchainService toolchains =
                project.getExtensions().getByType(JavaToolchainService.class);
        JavaPluginExtension javaExtension =
                project.getExtensions().getByType(JavaPluginExtension.class);
        Provider<JavaLauncher> launcher = toolchains.launcherFor(javaExtension.getToolchain());
        Provider<String> javaHome = launcher.map(l ->
                l.getMetadata().getInstallationPath().getAsFile().getAbsolutePath());
        Provider<Integer> javaVersion = launcher.map(l ->
                l.getMetadata().getLanguageVersion().asInt());
        Provider<String> javaExecutable = launcher.map(l ->
                l.getExecutablePath().getAsFile().getAbsolutePath());

        // jdeskGenerateBindings: compileJava already runs the jdesk-codegen annotation
        // processor (jdeskCodegen extends annotationProcessor); we add the TS output
        // option + output-directory tracking there and expose a lifecycle task.
        tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class, compile -> {
            TsBindingArguments tsArguments = objects.newInstance(TsBindingArguments.class);
            tsArguments.getTsOutputDir().set(frontend.getTsOutputDir());
            compile.getOptions().getCompilerArgumentProviders().add(tsArguments);
        });
        TaskProvider<Task> generateBindings = tasks.register("jdeskGenerateBindings", t -> {
            t.setGroup(GROUP);
            t.setDescription("Generates Java command registries and TypeScript bindings"
                    + " (compileJava runs the jdesk-codegen annotation processor;"
                    + " TypeScript goes to jdesk.frontend.tsOutputDir).");
            t.dependsOn(tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME));
        });

        tasks.register("jdeskDoctor", JDeskDoctorTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Verifies JDK toolchain, OS, WebView runtime, frontend"
                    + " tool, packaging tools and jdesk configuration; lists every"
                    + " problem with remediation.");
            t.getApplicationId().set(extension.getApplicationId());
            t.getMainModule().set(extension.getMainModule());
            t.getMainClass().set(extension.getMainClass());
            t.getFrontendTool().set(frontend.getBuildCommand()
                    .map(command -> command.isEmpty() ? null : command.get(0)));
            t.getToolchainVersion().set(javaVersion);
            t.getToolchainHome().set(javaHome);
            t.getWebView2Loader().set(project.getProviders().gradleProperty("jdeskWebView2Loader"));
        });

        TaskProvider<JDeskFrontendBuildTask> frontendBuild =
                tasks.register("jdeskFrontendBuild", JDeskFrontendBuildTask.class, t -> {
                    t.setGroup(GROUP);
                    t.setDescription("Builds the frontend bundle by running"
                            + " jdesk.frontend.buildCommand in the frontend directory"
                            + " (NO-SOURCE when no frontend is configured).");
                    t.getFrontendDirectory().set(frontend.getDirectory());
                    t.getBuildCommand().set(frontend.getBuildCommand());
                    t.getStaticCopy().set(frontend.getStaticCopy());
                    t.getDistDirectory().set(frontend.getDistDirectory());
                    t.getSources().from(frontendSources(objects, frontend));
                    // compileJava writes generated TypeScript bindings into the
                    // frontend tree (tsOutputDir); order it first so the frontend
                    // build sees fresh bindings and Gradle's implicit-dependency
                    // validation is satisfied.
                    t.dependsOn(tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME));
                });

        // Ship the built frontend inside the jar under /web so packaged (classpath)
        // apps can serve it; harmless no-op when no frontend is configured. Wired to
        // the jar rather than processResources so backend-only builds (classes, test)
        // never trigger the frontend build.
        tasks.named(JavaPlugin.JAR_TASK_NAME, Jar.class, t -> {
            t.dependsOn(frontendBuild);
            Provider<Object> dist = frontend.getDistDirectory()
                    .<Object>map(d -> d)
                    .orElse(Collections.emptyList());
            t.from(dist, spec -> spec.into("web"));
        });

        // Classes-based launches no longer see /web on the classpath, so keep the
        // conventional `run` task (application plugin) working: build the frontend and
        // point jdesk.assets.dir at the dist unless the build already set it.
        tasks.withType(JavaExec.class)
                .matching(t -> t.getName().equals(ApplicationPlugin.TASK_RUN_NAME))
                .configureEach(t -> {
                    t.dependsOn(frontendBuild);
                    Provider<File> dist = frontend.getDistDirectory()
                            .map(directory -> directory.getAsFile());
                    t.doFirst("jdeskAssetsDirFallback", task -> {
                        JavaExec exec = (JavaExec) task;
                        if (!exec.getSystemProperties().containsKey("jdesk.assets.dir")) {
                            File distDir = dist.getOrNull();
                            if (distDir != null && distDir.isDirectory()) {
                                exec.systemProperty("jdesk.assets.dir", distDir.getAbsolutePath());
                            }
                        }
                        // Forward -Djdesk.* flags from the Gradle CLI to the app JVM so
                        // `./gradlew run -Djdesk.console.forward=true` (or -Djdesk.automation=true)
                        // reaches the app, which runs in a forked process.
                        System.getProperties().forEach((k, v) -> {
                            String key = String.valueOf(k);
                            if (key.startsWith("jdesk.")
                                    && !exec.getSystemProperties().containsKey(key)) {
                                exec.systemProperty(key, String.valueOf(v));
                            }
                        });
                    });
                });

        tasks.register("jdeskDev", JDeskDevTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Starts the frontend HMR server and supervises the Java"
                    + " application; recompiles and restarts it after source changes.");
            t.dependsOn(tasks.named(JavaPlugin.CLASSES_TASK_NAME), generateBindings);
            t.getDevCommand().set(frontend.getDevCommand());
            t.getFrontendDirectory().set(frontend.getDirectory());
            t.getDevUrl().set(frontend.getDevUrl());
            // Static frontend mode inputs (used when no devCommand is configured).
            t.getFrontendBuildCommand().set(frontend.getBuildCommand());
            t.getFrontendStaticCopy().set(frontend.getStaticCopy());
            t.getFrontendDistDirectory().set(frontend.getDistDirectory());
            t.getFrontendSources().from(frontendSources(objects, frontend));
            t.getMainClass().set(extension.getMainClass());
            t.getMainModule().set(extension.getMainModule());
            t.getJavaExecutable().set(javaExecutable);
            t.getJavaReload().set(development.getJavaReload());
            t.getReloadCommand().set(development.getReloadCommand());
            t.getReloadDebounceMillis().set(development.getReloadDebounceMillis());
            t.getReloadWorkingDirectory().set(layout.getProjectDirectory());
            SourceSetContainer sourceSets =
                    project.getExtensions().getByType(SourceSetContainer.class);
            SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            t.getRuntimeClasspath().from(main.getRuntimeClasspath());
            t.dependsOn(project.getConfigurations().getByName(
                    JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            t.getApplicationResources().from(
                    project.getProviders().provider(() -> main.getOutput().getResourcesDir()));
            t.getReloadSources().from(main.getAllJava().getSourceDirectories());
            t.getReloadSources().from(main.getResources().getSourceDirectories());
            t.getReloadSources().from(development.getReloadSources());
        });

        TaskProvider<Jar> jar = tasks.named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        TaskProvider<JDeskRuntimeImageTask> runtimeImage =
                tasks.register("jdeskRuntimeImage", JDeskRuntimeImageTask.class, t -> {
                    t.setGroup(GROUP);
                    t.setDescription("Creates a trimmed JDK runtime image with jlink;"
                            + " module set computed by the toolchain's jdeps over the"
                            + " runtime classpath.");
                    t.getAppClasspath().from(jar.flatMap(Jar::getArchiveFile));
                    t.getAppClasspath().from(
                            project.getConfigurations().getByName(
                                    JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
                    t.getJavaHome().set(javaHome);
                    t.getMultiRelease().set(javaVersion);
                    t.getOutputDirectory().convention(
                            layout.getBuildDirectory().dir("jdesk/runtime-image"));
                });

        String projectVersion = String.valueOf(project.getVersion());
        TaskProvider<JDeskPackageTask> packageTask =
                tasks.register("jdeskPackage", JDeskPackageTask.class, t -> {
                    t.setGroup(GROUP);
                    t.setDescription("Builds a modular platform app-image with jpackage"
                            + " from the runtime image and named application modules.");
                    t.getRuntimeImage().set(
                            runtimeImage.flatMap(JDeskRuntimeImageTask::getOutputDirectory));
                    t.getMainJar().set(jar.flatMap(Jar::getArchiveFile));
                    t.getRuntimeClasspathJars().from(
                            project.getConfigurations().getByName(
                                    JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
                    t.getMainClass().set(extension.getMainClass());
                    t.getMainModule().set(extension.getMainModule());
                    t.getApplicationId().set(extension.getApplicationId());
                    t.getUrlSchemes().set(extension.getDeepLink().getSchemes());
                    t.getUsageDescriptions().set(extension.getDeepLink().getUsageDescriptions());
                    t.getFileAssociations().set(extension.getFileAssociations());
                    t.getAppIcon().set(extension.getAppIcon());
                    t.getImageName().convention(extension.getApplicationId()
                            .map(id -> id.substring(id.lastIndexOf('.') + 1)));
                    t.getAppVersion().convention(projectVersion);
                    t.getJavaHome().set(javaHome);
                    t.getStagingDirectory().convention(
                            layout.getBuildDirectory().dir("jdesk/package-input"));
                    t.getDestination().convention(
                            layout.getBuildDirectory().dir("jdesk/package"));
                });

        tasks.register("jdeskInstaller", JDeskInstallerTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Builds a platform installer (DMG/PKG, MSI/EXE, DEB/RPM) from"
                    + " the jdeskPackage app-image via jpackage, on the target OS only."
                    + " UNSIGNED unless a signing identity is configured.");
            t.dependsOn(packageTask);
            t.getAppImageDirectory().set(
                    packageTask.flatMap(JDeskPackageTask::getDestination));
            t.getImageName().set(extension.getApplicationId()
                    .map(id -> id.substring(id.lastIndexOf('.') + 1)));
            t.getAppVersion().convention(projectVersion);
            t.getInstallerType().set(
                    project.getProviders().gradleProperty("jdeskInstallerType"));
            t.getMacSigningIdentity().set(extension.getSigning().getMacSigningIdentity());
            t.getJavaHome().set(javaHome);
            t.getDestination().convention(
                    layout.getBuildDirectory().dir("jdesk/installer"));
        });

        tasks.register("jdeskNativeSmokeTest", JDeskNativeSmokeTestTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Launches the packaged app-image with --jdesk-smoke and"
                    + " requires exit code 0 within a timeout (real launch, no fake"
                    + " pass).");
            t.dependsOn(packageTask);
            t.getPackageDirectory().set(packageTask.flatMap(JDeskPackageTask::getDestination));
            t.getImageName().set(packageTask.flatMap(JDeskPackageTask::getImageName));
            t.getTimeoutSeconds().convention(180L);
            t.getSmokeArgs().convention(List.of("--jdesk-smoke"));
            t.getLogFile().convention(
                    layout.getBuildDirectory().file("jdesk/native-smoke/output.log"));
        });

        tasks.register("jdeskVerifyEvidence", JDeskVerifyEvidenceTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Runs the JDesk evidence verifier (VerifyMain) against the"
                    + " evidence directory (default build/evidence).");
            t.getVerifierClasspath().from(jdeskTestkit);
            t.getEvidenceDirectory().convention(layout.getBuildDirectory().dir("evidence"));
        });
    }

    /**
     * Frontend sources for up-to-date checking: everything under the frontend directory
     * except {@code node_modules}, {@code .git} and the dist directory (a task output).
     * Absent frontend directory yields an empty collection (NO-SOURCE).
     */
    private static Provider<Object> frontendSources(
            ObjectFactory objects, JDeskFrontendExtension frontend) {
        return frontend.getDirectory().<Object>map(dir -> {
            ConfigurableFileTree tree = objects.fileTree();
            tree.setDir(dir);
            tree.exclude("node_modules/**", ".git/**");
            File dist = frontend.getDistDirectory().getAsFile().getOrNull();
            if (dist != null) {
                Path dirPath = dir.getAsFile().toPath().toAbsolutePath().normalize();
                Path distPath = dist.toPath().toAbsolutePath().normalize();
                if (distPath.startsWith(dirPath) && !distPath.equals(dirPath)) {
                    String relative = dirPath.relativize(distPath).toString()
                            .replace(File.separatorChar, '/');
                    tree.exclude(relative + "/**");
                }
            }
            return tree;
        }).orElse(Collections.emptyList());
    }

    private static List<String> defaultReloadCommand(Project project) {
        List<String> command = new ArrayList<>();
        File root = project.getRootProject().getProjectDir();
        if (OsSupport.isWindows()) {
            File wrapper = new File(root, "gradlew.bat");
            if (wrapper.isFile()) {
                command.add("cmd");
                command.add("/c");
                command.add(wrapper.getAbsolutePath());
            }
        } else {
            File wrapper = new File(root, "gradlew");
            if (wrapper.isFile()) {
                command.add(wrapper.getAbsolutePath());
            }
        }
        if (command.isEmpty()) {
            File gradleHome = project.getGradle().getGradleHomeDir();
            String executable = OsSupport.isWindows() ? "gradle.bat" : "gradle";
            command.add(gradleHome == null
                    ? executable
                    : new File(gradleHome, "bin/" + executable).getAbsolutePath());
        }
        String classesTask = project.getPath().equals(":")
                ? ":classes"
                : project.getPath() + ":classes";
        command.add(classesTask);
        command.add("--console=plain");
        command.add("--no-configuration-cache");
        return List.copyOf(command);
    }
}
