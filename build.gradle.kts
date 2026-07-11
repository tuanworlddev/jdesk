// Root build: intentionally minimal. All shared logic lives in build-logic conventions.
tasks.register("printVersion") {
    val v = project.version.toString()
    doLast { println(v) }
}
