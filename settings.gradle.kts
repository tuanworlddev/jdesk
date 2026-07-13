pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Uploads the existing maven-publish publications to the Maven Central Portal.
    // Auto-applies com.gradleup.nmcp to every project and aggregates their publications;
    // run `./gradlew publishAggregationToCentralPortal` to push a staged deployment.
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

// Central Portal credentials — supplied at publish time, never checked in:
//   -PmavenCentralUsername=<token-user> -PmavenCentralPassword=<token-pass>
// (or the MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD environment variables).
// Artifacts must still be signed: pass -PsigningKey / -PsigningPassword (see
// jdesk.publishing-conventions). Absent credentials only fail at publish time, so the
// build still configures for everyday work.
val centralUser = providers.gradleProperty("mavenCentralUsername")
    .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME")).orNull
val centralPassword = providers.gradleProperty("mavenCentralPassword")
    .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD")).orNull
nmcpSettings {
    centralPortal {
        if (centralUser != null) username = centralUser
        if (centralPassword != null) password = centralPassword
        // Default USER_MANAGED: the deployment waits in the Central Portal UI for you to
        // verify and release. Pass -PcentralPublishingType=AUTOMATIC to upload-and-release
        // in one shot (only releases if Central's validation passes).
        publishingType = providers.gradleProperty("centralPublishingType").orNull ?: "USER_MANAGED"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jdesk"

include(
    ":modules:jdesk-api",
    ":modules:jdesk-bom",
    ":modules:jdesk-runtime",
    ":modules:jdesk-automation",
    ":modules:jdesk-webview-spi",
    ":modules:jdesk-native-ffm",
    ":modules:jdesk-platform-windows",
    ":modules:jdesk-platform-macos",
    ":modules:jdesk-platform-linux",
    ":modules:jdesk-codegen",
    ":modules:jdesk-cli",
    ":modules:jdesk-gradle-plugin",
    ":modules:jdesk-packager",
    ":modules:jdesk-testkit",
    ":modules:jdesk-updater",
    ":modules:jdesk-instance",
    ":test-apps:native-smoke",
    ":test-apps:security-probe",
    ":test-apps:packaging-probe",
    ":examples:hello-vanilla",
    ":examples:jdesk-notes",
)
