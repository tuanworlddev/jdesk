plugins {
    id("jdesk.library-conventions")
    id("jdesk.coverage-conventions")
}
description = "JDesk platform SPI: window, WebView, dispatcher contracts."
dependencies { api(project(":modules:jdesk-api")) }

// CupsPrinting spawns the OS `lp` binary — POSIX-native execution glue. Its process branch
// (waitFor/exitValue) only runs where `lp` exists and is exercised by the macOS/Linux native
// CI lanes (native-smoke `java:print-file-plumbing`), never on Windows. Excluding it keeps
// the unit-coverage gate meaningful and identical on every OS, so `./gradlew check` passes on
// a Windows checkout instead of dipping to ~0.78 purely because `lp` is absent. The pure,
// portable `buildCommand` option mapping stays covered by CupsPrintingTest.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(
        sourceSets["main"].output.classesDirs.asFileTree.matching {
            exclude("module-info.class")
            exclude("**/CupsPrinting.class")
        }
    )
}
