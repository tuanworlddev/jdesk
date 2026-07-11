plugins { id("jdesk.library-conventions") }
description = "JDesk runtime core: lifecycle, IPC, capabilities, asset resolver. No native classes."
dependencies {
    api(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-webview-spi"))
}
