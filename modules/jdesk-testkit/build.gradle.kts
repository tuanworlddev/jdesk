plugins { id("jdesk.library-conventions") }
description = "JDesk test support: evidence writer, in-process test fixtures (never in production variants)."
dependencies {
    api(project(":modules:jdesk-api"))
    api(project(":modules:jdesk-webview-spi"))
}
