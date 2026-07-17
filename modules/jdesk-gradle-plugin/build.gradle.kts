plugins {
    id("jdesk.java-conventions")
    `java-gradle-plugin`
    id("jdesk.publishing-conventions")
    // Publishes this plugin (and its marker artifact) to the Gradle Plugin Portal via
    // `./gradlew :modules:jdesk-gradle-plugin:publishPlugins`. Credentials come from
    // ~/.gradle/gradle.properties (gradle.publish.key / gradle.publish.secret) or the
    // GRADLE_PUBLISH_KEY / GRADLE_PUBLISH_SECRET environment variables.
    id("com.gradle.plugin-publish") version "2.1.1"
}
description = "JDesk Gradle application plugin (dev.jdesk.application)."
gradlePlugin {
    website = "https://jdesk.dev"
    vcsUrl = "https://github.com/tuanworlddev/jdesk.git"
    plugins {
        create("jdeskApplication") {
            id = "dev.jdesk.application"
            implementationClass = "dev.jdesk.gradle.JDeskApplicationPlugin"
            displayName = "JDesk application plugin"
            description = "Builds, runs, and packages JDesk desktop apps (web frontend + Java runtime)."
            tags = listOf("jdesk", "desktop", "application", "webview", "packaging")
        }
    }
}

dependencies {
    implementation(project(":modules:jdesk-packager"))
    // Local Gradle distribution dependency (not an external module; locks unaffected).
    testImplementation(gradleTestKit())
}

// The plugin needs its own version at runtime (default coordinates of the
// jdeskCodegen/jdeskTestkit configurations). Generated as a resource so it also works
// under TestKit's injected classes-dir classpath where no jar manifest exists.
val versionResourceDir = layout.buildDirectory.dir("generated/jdesk-version")
val generateVersionResource = tasks.register("generateVersionResource") {
    val version = project.version.toString()
    val outputDir = versionResourceDir
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/jdesk/gradle/jdesk-plugin-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\n")
    }
}
sourceSets.main {
    resources.srcDir(generateVersionResource)
}

// Framework artifacts for TestKit functional tests. Consumer builds created in @TempDir
// mimic published artifacts by pointing the plugin's jdeskCodegen/jdeskTestkit
// configurations and their compile classpath at these jars (spec 14: consumer tests
// must not rely on project dependencies inside the consumer build itself).
val functionalTestArtifacts: Configuration = configurations.create("functionalTestArtifacts") {
    isCanBeConsumed = false
}
dependencies {
    functionalTestArtifacts(project(":modules:jdesk-api"))
    functionalTestArtifacts(project(":modules:jdesk-codegen"))
    functionalTestArtifacts(project(":modules:jdesk-testkit"))
}
// Class (not script lambda) so the provider serializes under the configuration cache.
class FrameworkClasspathArgument(
    @get:Classpath val artifacts: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf(
        "-Djdesk.test.framework.classpath=" +
            artifacts.files.joinToString(File.pathSeparator) { it.absolutePath },
    )
}

tasks.test {
    jvmArgumentProviders.add(FrameworkClasspathArgument(functionalTestArtifacts))
    // TestKit spawns real Gradle builds; keep them off the CI-shared daemon settings.
    systemProperty("org.gradle.testkit.debug", "false")
}
