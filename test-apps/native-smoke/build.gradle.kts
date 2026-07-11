plugins {
    id("jdesk.java-conventions")
    application
}
description = "JDesk native-smoke test application (real native runs only; no fake providers)."
dependencies {
    implementation(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-runtime"))
}
application { mainClass = "dev.jdesk.testapps.nativesmoke.Main" }
