plugins {
    id("jdesk.library-conventions")
    id("jdesk.coverage-conventions")
}
description = "JDesk public application API. No platform dependencies."

val versionResourceDir = layout.buildDirectory.dir("generated/jdesk-version")
val generateVersionResource = tasks.register("generateVersionResource") {
    val frameworkVersion = project.version.toString()
    val outputDir = versionResourceDir
    inputs.property("version", frameworkVersion)
    outputs.dir(versionResourceDir)
    doLast {
        val file = outputDir.get().file("dev/jdesk/api/jdesk-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$frameworkVersion\n")
    }
}
sourceSets.main {
    resources.srcDir(generateVersionResource)
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "jdesk.apiBaseline.path",
        layout.projectDirectory.file("src/test/resources/api/dev.jdesk.api.txt").asFile.absolutePath
    )
    System.getProperty("jdesk.apiBaseline.update")?.let {
        systemProperty("jdesk.apiBaseline.update", it)
    }
}
