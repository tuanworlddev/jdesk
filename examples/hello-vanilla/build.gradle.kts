plugins {
    id("jdesk.java-conventions")
    application
}
description = "JDesk hello-vanilla example: a real consumer app built only on public APIs."

// Examples consume the framework as project dependencies until Maven publication lands
// (ADR-002). A published consumer would depend on dev.jdesk:jdesk-api /
// dev.jdesk:jdesk-runtime / dev.jdesk:jdesk-codegen instead.
dependencies {
    implementation(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-runtime"))
    runtimeOnly(project(":modules:jdesk-automation"))
    compileOnly(libs.jackson.databind)
    // Compile-time command registration + TS binding generation (ADR-005).
    annotationProcessor(project(":modules:jdesk-codegen"))
    // The platform adapter is selected per OS at launch, mirroring native-smoke:
    //   ./gradlew :examples:hello-vanilla:run -PjdeskPlatform=macos
    // No adapter => startup fails loudly with the exactly-one-provider diagnostic.
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        runtimeOnly(project(":modules:jdesk-platform-$platform"))
    }
}

application {
    mainModule = "dev.jdesk.examples.hello"
    mainClass = "dev.jdesk.examples.hello.Main"
}

tasks.named<JavaExec>("run") {
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        jvmArgs("--enable-native-access=dev.jdesk.platform.$platform", "--illegal-native-access=deny")
    }
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        // AppKit requires the process's first thread; the plain `java` launcher runs
        // main() on a secondary thread on macOS unless told otherwise.
        jvmArgs("-XstartOnFirstThread")
    }
    // `run` serves the UI straight from the source tree: RuntimeOptions reads
    // jdesk.assets.dir (a directory). Packaged mode instead ships the same files on
    // the classpath under /web via the jdesk Gradle plugin (Phase 7 packaging).
    systemProperty(
        "jdesk.assets.dir",
        layout.projectDirectory.dir("src/main/resources/web").asFile.absolutePath,
    )
}
