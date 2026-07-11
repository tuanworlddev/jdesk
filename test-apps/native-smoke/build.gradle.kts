plugins {
    id("jdesk.java-conventions")
    application
}
description = "JDesk native-smoke test application (real native runs only; no fake providers)."
dependencies {
    implementation(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-runtime"))
    implementation(project(":modules:jdesk-webview-spi"))
    implementation(project(":modules:jdesk-testkit"))
    // The platform adapter is selected per-OS at run time; native runs add it via
    // -PjdeskPlatform. No adapter => startup fails loudly (never a fake).
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) {
        runtimeOnly(project(":modules:jdesk-platform-$platform"))
    }
}
application { mainClass = "dev.jdesk.testapps.nativesmoke.Main" }

tasks.named<JavaExec>("run") {
    // Test/dev launch on the classpath: ALL-UNNAMED is acceptable here, production
    // images grant native access to named modules only (ADR-001).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("jdesk.evidence.dir",
        providers.gradleProperty("jdeskEvidenceDir").getOrElse("build/evidence"))
    providers.gradleProperty("jdeskWebView2Loader").orNull?.let {
        systemProperty("jdesk.windows.webview2loader", it)
    }
    // The Win32/AppKit UI thread must be the process main thread where required.
    // Gradle's JavaExec forks a fresh JVM whose main thread runs the app: OK.
    // AppKit additionally requires main() to run on the process's first thread.
    if (providers.gradleProperty("jdeskPlatform").orNull == "macos") {
        jvmArgs("-XstartOnFirstThread")
    }
}

tasks.register<JavaExec>("verifyEvidence") {
    group = "verification"
    description = "Recomputes checksums and validates evidence manifests (spec 18)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.jdesk.testkit.evidence.VerifyMain"
    args(providers.gradleProperty("jdeskEvidenceVerifyDir")
        .getOrElse(layout.buildDirectory.dir("evidence").get().asFile.absolutePath))
}

tasks.named<JavaExec>("run") {
    systemProperty("jdesk.smoke.stress",
        providers.gradleProperty("jdeskStress").getOrElse("false"))
}
