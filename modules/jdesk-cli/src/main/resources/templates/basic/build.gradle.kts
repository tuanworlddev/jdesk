plugins {
    id("dev.jdesk.application")@PLUGIN_VERSION@
    application
}

group = "@PACKAGE@"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val jdeskVersion = "@JDESK_VERSION@"
val platform = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
    else -> "linux"
}

dependencies {
    implementation("dev.jdesk:jdesk-api:$jdeskVersion")
    implementation("dev.jdesk:jdesk-runtime:$jdeskVersion")
    runtimeOnly("dev.jdesk:jdesk-platform-$platform:$jdeskVersion")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.19.0")
}

val javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}

jdesk {
    // Classpath (non-modular) app — no module-info.java, less ceremony for small apps.
    applicationId.set("@APP_ID@")
    mainClass.set("@PACKAGE@.Main")
    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        buildCommand.set(javaLauncher.map {
            listOf(it.executablePath.asFile.absolutePath, "Build.java")
        })
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))
    }
}

application {
    mainClass.set("@PACKAGE@.Main")
}

// `./gradlew run` launches the app on this OS. It builds the frontend first and serves it
// over jdesk://app/. Classpath apps grant native access to all code (ALL-UNNAMED).
tasks.named<JavaExec>("run") {
    dependsOn("jdeskFrontendBuild")
    doNotTrackState("launches a desktop application")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        jvmArgs("-XstartOnFirstThread")
    }
    systemProperty("jdesk.assets.dir",
        layout.projectDirectory.dir("ui/dist").asFile.absolutePath)
}

// Short aliases so you don't memorize the long task names:
//   ./gradlew run       launch the app          ./gradlew doctor    check your environment
//   ./gradlew pkg       build a native image    ./gradlew bindings  regenerate TS bindings
tasks.register("doctor") {
    group = "jdesk"
    description = "Alias for jdeskDoctor."
    dependsOn("jdeskDoctor")
}
tasks.register("bindings") {
    group = "jdesk"
    description = "Alias for jdeskGenerateBindings."
    dependsOn("jdeskGenerateBindings")
}
tasks.register("pkg") {
    group = "jdesk"
    description = "Alias for jdeskPackage (native app image)."
    dependsOn("jdeskPackage")
}
