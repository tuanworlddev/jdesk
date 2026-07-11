plugins { id("jdesk.library-conventions") }
description = "JDesk test support: evidence writer/verifier, fixtures (never in production variants)."
dependencies {
    api(project(":modules:jdesk-api"))
    api(project(":modules:jdesk-webview-spi"))
    implementation(libs.jackson.databind)
}
