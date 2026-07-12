plugins {
    id("jdesk.library-conventions")
}

description = "Optional token-gated HTTP automation provider for JDesk E2E tests."

dependencies {
    implementation(project(":modules:jdesk-runtime"))
    implementation(libs.jackson.databind)
}
