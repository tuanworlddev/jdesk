plugins {
    id("dev.jdesk.application")@PLUGIN_VERSION@
    application
}

group = "@PACKAGE@"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

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

jdesk {
    // Classpath (non-modular) app — no module-info.java, less ceremony for small apps.
    applicationId.set("@APP_ID@")
    mainClass.set("@PACKAGE@.Main")
    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(listOf("npm", "run", "build"))
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))
    }
}

application {
    mainClass.set("@PACKAGE@.Main")
}

// `./gradlew run` launches the app on this OS (builds the frontend first, serves it over
// jdesk://app/). For live frontend reload while developing, use `./gradlew dev` instead.
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
//   ./gradlew run   launch     ./gradlew dev   dev loop with HMR
//   ./gradlew pkg   package    ./gradlew doctor  environment check   ./gradlew bindings
tasks.register("dev") {
    group = "jdesk"
    description = "Alias for jdeskDev (frontend hot-reload)."
    dependsOn("jdeskDev")
}
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
