plugins {
    id("jdesk.library-conventions")
    id("jdesk.coverage-conventions")
}
description = "JDesk runtime core: lifecycle, IPC, capabilities, asset resolver. No native classes."
dependencies {
    api(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-webview-spi"))
    implementation(project(":modules:jdesk-instance"))
    implementation(libs.jackson.databind)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(project(":modules:jdesk-automation"))
}
