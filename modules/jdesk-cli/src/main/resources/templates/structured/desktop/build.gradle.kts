plugins {
    id("dev.jdesk.application")@PLUGIN_VERSION@
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

val jdeskVersion = "@JDESK_VERSION@"
val platform = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
    else -> "linux"
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation("dev.jdesk:jdesk-api:$jdeskVersion")
    implementation("dev.jdesk:jdesk-runtime:$jdeskVersion")
    runtimeOnly("dev.jdesk:jdesk-platform-$platform:$jdeskVersion")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.19.0")
}

val javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
}

jdesk {
    applicationId.set("@APP_ID@")
    mainModule.set("@APP_ID@.desktop")
    mainClass.set("@PACKAGE@.desktop.Main")
    frontend {
        directory.set(rootProject.layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(javaLauncher.map {
            listOf(it.executablePath.asFile.absolutePath, "Build.java")
        })
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(rootProject.layout.projectDirectory.dir("ui/dist"))
    }
    development {
        reloadSources.from(
            rootProject.file("domain/src/main"),
            rootProject.file("application/src/main"),
            rootProject.file("infrastructure/src/main")
        )
    }
}
