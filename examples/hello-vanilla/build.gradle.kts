plugins {
    id("jdesk.java-conventions")
    application
}
description = "JDesk hello-vanilla example application."
dependencies {
    implementation(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-runtime"))
}
application { mainClass = "dev.jdesk.examples.hello.Main" }
