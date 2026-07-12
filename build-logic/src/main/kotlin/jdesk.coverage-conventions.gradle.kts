// Coverage gate for core modules (spec section 21): line >= 80%, branch >= 70%.
// module-info is excluded as generated/descriptor boilerplate (reviewed explicit rule).
plugins {
    java
    jacoco
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    // Use the compiled class dirs directly (built-by compileJava) via asFileTree so the
    // task dependency is preserved. Reading classDirectories.files eagerly and wrapping the
    // result in a plain fileTree strips that dependency (Gradle 9 validation error).
    classDirectories.setFrom(
        sourceSets["main"].output.classesDirs.asFileTree.matching {
            exclude("module-info.class")
        }
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
}
