plugins {
    id("jdesk.library-conventions")
    id("jdesk.coverage-conventions")
}
description = "JDesk runtime core: lifecycle, IPC, capabilities, asset resolver. No native classes."
dependencies {
    api(project(":modules:jdesk-api"))
    api(project(":modules:jdesk-webview-spi"))
    implementation(libs.jackson.databind)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
}
