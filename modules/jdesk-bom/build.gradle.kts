plugins {
    `java-platform`
    id("jdesk.publishing-conventions")
}

description = "JDesk bill of materials: aligns versions of all published JDesk modules."

dependencies {
    constraints {
        api(project(":modules:jdesk-api"))
        api(project(":modules:jdesk-runtime"))
        api(project(":modules:jdesk-webview-spi"))
        api(project(":modules:jdesk-native-ffm"))
        api(project(":modules:jdesk-codegen"))
        api(project(":modules:jdesk-packager"))
        api(project(":modules:jdesk-testkit"))
        api(project(":modules:jdesk-platform-windows"))
        api(project(":modules:jdesk-platform-macos"))
        api(project(":modules:jdesk-platform-linux"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
}
