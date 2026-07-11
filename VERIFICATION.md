# JDesk Verification Status

This file is updated **only from machine-generated reports** (spec sections 18, 23). A cell may claim a pass only if evidence exists under `build/evidence/<run-id>/` (local) or as a CI artifact, produced by the same commit being tested. Statuses: `NOT STARTED`, `IN PROGRESS`, `PASS (evidence: <run-id or CI link>)`, `FAIL`, `UNVERIFIED`, `BLOCKED`.

## Native smoke

| Platform | Status | Evidence | WebView version |
| --- | --- | --- | --- |
| Windows x64 | NOT STARTED | — | — |
| macOS ARM64 | NOT STARTED | — | — |
| Linux x64 | NOT STARTED | — | — |

## Package smoke

| Platform | Status | Evidence | Package checksum |
| --- | --- | --- | --- |
| Windows x64 | NOT STARTED | — | — |
| macOS ARM64 | NOT STARTED | — | — |
| Linux x64 | NOT STARTED | — | — |

## Security probes (section 17.6)

| Platform | Status | Evidence |
| --- | --- | --- |
| Windows x64 | NOT STARTED | — |
| macOS ARM64 | NOT STARTED | — |
| Linux x64 | NOT STARTED | — |

## Stress / leak (section 17.5)

| Platform | Status | Evidence | RSS baseline |
| --- | --- | --- | --- |
| Windows x64 | NOT STARTED | — | — |
| macOS ARM64 | NOT STARTED | — | — |
| Linux x64 | NOT STARTED | — | — |

## Unit / functional gates

| Gate | Status | Evidence |
| --- | --- | --- |
| Core unit tests (JDK 25) | PASS (523 tests, 0 failures) | CI run 29136815933 (`core-unit-jdk25`, ubuntu, Temurin 25) + local Gradle reports |
| Coverage (line >= 80%, branch >= 70%) | PASS | JaCoCo verification in `check`; api 89.7/89.6, runtime 89.4/84.6, ffm 97.0/100, spi 86.7/100 |
| Gradle plugin TestKit functional | IN PROGRESS (plugin is a Phase 3 stub; job runs, no functional tests yet) | CI run 29136815933 (`gradle-plugin-functional`) |
| Deterministic codegen (golden, double-run) | NOT STARTED | — |
| Configuration-cache compatibility | PASS (all local builds run with configuration cache on) | gradle.properties + CI logs |
| Dependency verification | PASS (sha256 metadata, 97 components; locks per project) | gradle/verification-metadata.xml; CI resolves with verification active |

## Secondary architectures

| Platform | Status | Note |
| --- | --- | --- |
| Windows ARM64 | NOT STARTED | Only after Windows x64 gate |
| macOS Intel | NOT STARTED | Only after macOS ARM64 gate |
| Linux ARM64 | NOT STARTED | Only after Linux x64 gate |
