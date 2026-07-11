plugins { id("jdesk.library-conventions") }
description = "JDesk linux platform adapter (FFM)."
dependencies {
    api(project(":modules:jdesk-webview-spi"))
    implementation(project(":modules:jdesk-native-ffm"))
}
