# JDesk Verification Status

This file is updated **only from machine-generated reports** (spec sections 18, 23). A cell may claim a pass only if evidence exists under `build/evidence/<run-id>/` (local) or as a CI artifact, produced by the same commit being tested. Statuses: `NOT STARTED`, `IN PROGRESS`, `PASS (evidence: <run-id or CI link>)`, `FAIL`, `UNVERIFIED`, `BLOCKED`.

## Native smoke

| Platform | Status | Evidence | WebView version |
| --- | --- | --- | --- |
| Windows x64 | PASS | CI runs 29137796715 + 29137919391 (stress), artifact `windows-x64-native-evidence`, provider `windows-webview2`, evidence verifier green | WebView2 Evergreen on Windows Server 2025 |
| macOS ARM64 | PASS | Local real hardware (macOS 26.5.1 arm64), runs 1783741626-1b31e8d3bd4403a7 + 1783741637-3a7dffd9377a2d6b (stress), commit 9cd65d40c clean, verifier green, archived ~/JDesk-evidence-archive | WKWebView (system WebKit) |
| Linux x64 | PASS | CI run 29139086672 (branch), provider `linux-webkitgtk`, evidence `1783743433-2f0db9f6f5b9b528`, verifier green | WebKitGTK 4.1 (libwebkit2gtk-4.1) |

## Package smoke

| Platform | Status | Evidence | Package checksum |
| --- | --- | --- | --- |
| Windows x64 | see CI run 29139506086 (`package-windows-x64`) | jpackage app-image `JDeskSmoke.exe` launched without Gradle, category `package` | — |
| macOS ARM64 | PASS | jpackage app-image `JDeskSmoke.app` executed directly; run 1783741694-0ba04a314ebd5e40, category `package`, 21/21, exit 0 | — |
| Linux x64 | PASS | CI run 29140603452 `package-linux-x64`: app-image launched without Gradle under Xvfb (category `package`) + DEB installer built (`jdesksmoke_1.0.0_amd64.deb`, sha256 2c12b580…, UNSIGNED) | — |

## Security probes (section 17.6)

| Platform | Status | Evidence |
| --- | --- | --- |
| Windows x64 | PASS | CI run 29140030589 job `security-windows-x64`, provider `windows-webview2`, artifact `security-windows-x64-evidence`, verifier green |
| macOS ARM64 | PASS | Local run 1783744909-9ab8bd400421eeb1, provider `macos-wkwebview`, 22/22, archived |
| Linux x64 | PASS | CI run 29140030589 job `security-linux-x64`, provider `linux-webkitgtk`, artifact `security-linux-x64-evidence`, verifier green |

## Stress / leak (section 17.5)

| Platform | Status | Evidence | RSS baseline |
| --- | --- | --- | --- |
| Windows x64 | PASS (10,000 IPC round trips 0 mismatch in 5152 ms; 25/25 window cycles; pending counters zero) | CI run 29137919391 | 77,111,296 -> 181,665,792 bytes (recorded, no threshold yet) |
| macOS ARM64 | PASS (10,000 IPC round trips 0 mismatch in 509 ms; 25/25 window cycles; pending counters zero) | Local run 1783741637-3a7dffd9377a2d6b | recorded in evidence environment.json (baseline only) |
| Linux x64 | PASS (10,000 IPC round trips 0 mismatch in 6208 ms; 25/25 window cycles; pending counters zero) | CI run 29139086672 | 80.9 MB -> 373.6 MB (recorded, no threshold yet) |
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
