plugins {
    id("jdesk.java-conventions")
    application
    `maven-publish`
}

description = "JDesk project generator CLI."

application {
    mainModule = "dev.jdesk.cli"
    mainClass = "dev.jdesk.cli.JDeskCli"
    applicationName = "jdesk"
}

tasks.processResources {
    from(rootProject.file("gradlew")) {
        into("dev/jdesk/cli/wrapper")
    }
    from(rootProject.file("gradlew.bat")) {
        into("dev/jdesk/cli/wrapper")
    }
    from(rootProject.file("gradle/wrapper")) {
        into("dev/jdesk/cli/wrapper/gradle/wrapper")
    }
}

tasks.test {
    systemProperty("jdesk.source.root", rootProject.layout.projectDirectory.asFile.absolutePath)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
