pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jdesk"

include(
    ":modules:jdesk-api",
    ":modules:jdesk-runtime",
    ":modules:jdesk-webview-spi",
    ":modules:jdesk-native-ffm",
    ":modules:jdesk-platform-windows",
    ":modules:jdesk-platform-macos",
    ":modules:jdesk-platform-linux",
    ":modules:jdesk-codegen",
    ":modules:jdesk-gradle-plugin",
    ":modules:jdesk-packager",
    ":modules:jdesk-testkit",
    ":test-apps:native-smoke",
    ":test-apps:security-probe",
    ":test-apps:packaging-probe",
    ":examples:hello-vanilla",
)
