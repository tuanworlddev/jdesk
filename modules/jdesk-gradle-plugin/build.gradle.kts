plugins {
    id("jdesk.java-conventions")
    `java-gradle-plugin`
    `maven-publish`
}
description = "JDesk Gradle application plugin (dev.jdesk.application)."
gradlePlugin {
    plugins {
        create("jdeskApplication") {
            id = "dev.jdesk.application"
            implementationClass = "dev.jdesk.gradle.JDeskApplicationPlugin"
        }
    }
}
