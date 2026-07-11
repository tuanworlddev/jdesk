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
