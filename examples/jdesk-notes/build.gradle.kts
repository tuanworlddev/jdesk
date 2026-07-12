plugins {
    id("jdesk.java-conventions")
    application
}
description = "JDesk Notes example: a real note editor with native New/Open/Save/Save As dialogs."

// Consumes the framework as project dependencies (like hello-vanilla) until Maven
// publication lands (ADR-002).
dependencies {
    implementation(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-runtime"))
    runtimeOnly(project(":modules:jdesk-automation"))
    compileOnly(libs.jackson.databind)
    // Compile-time command registration + TS binding generation (ADR-005).
    annotationProcessor(project(":modules:jdesk-codegen"))
    // The platform adapter is selected per OS at launch:
    //   ./gradlew :examples:jdesk-notes:run -PjdeskPlatform=windows -PjdeskWebView2Loader=...
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        runtimeOnly(project(":modules:jdesk-platform-$platform"))
    }
}

application {
    mainModule = "dev.jdesk.examples.notes"
    mainClass = "dev.jdesk.examples.notes.Main"
}

tasks.named<JavaExec>("run") {
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        jvmArgs("--enable-native-access=dev.jdesk.platform.$platform", "--illegal-native-access=deny")
    }
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
    // Windows: point the WebView2 loader at a fetched WebView2Loader.dll when provided.
    providers.gradleProperty("jdeskWebView2Loader").orNull?.let {
        systemProperty("jdesk.windows.webview2loader", it)
    }
    // `run` serves the UI straight from the source tree (a directory).
    systemProperty(
        "jdesk.assets.dir",
        layout.projectDirectory.dir("src/main/resources/web").asFile.absolutePath,
    )
    // Opt-in token-gated automation endpoint (E2E driving): -PjdeskAutomation=true.
    if (providers.gradleProperty("jdeskAutomation").orNull == "true") {
        systemProperty("jdesk.automation", "true")
        systemProperty("jdesk.automation.dir",
            layout.buildDirectory.dir("automation").get().asFile.absolutePath)
    }
}
