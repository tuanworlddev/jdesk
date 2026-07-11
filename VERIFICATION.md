# JDesk Verification Status

This file is updated **only from machine-generated reports** (spec sections 18, 23). A cell may claim a pass only if evidence exists under `build/evidence/<run-id>/` (local) or as a CI artifact, produced by the same commit being tested. Statuses: `NOT STARTED`, `IN PROGRESS`, `PASS (evidence: <run-id or CI link>)`, `FAIL`, `UNVERIFIED`, `BLOCKED`.

## Native smoke

| Platform | Status | Evidence | WebView version |
| --- | --- | --- | --- |
| Windows x64 | PASS (28/28) | CI run 29151082737 job `windows-x64-native`, artifact `windows-x64-native-evidence`, provider `windows-webview2`, evidence verifier green | WebView2 Evergreen on Windows Server 2025 |
| macOS ARM64 | PASS (33/33) | Local real hardware (macOS arm64), runs 1783786098-4d5f87f18232b697 + 1783786141-cbdefa21a3dd2e5c (stress + 2 GiB stream), verifier `VERIFY OK: 2/2`, archived under `evidence/native-smoke/`. Runs are from the 2026-07-11 feature worktree (commit pending); they supersede 1783741626/1783741637 | WKWebView (system WebKit) |
| Linux x64 | PASS | CI run 29139086672 (branch), provider `linux-webkitgtk`, evidence `1783743433-2f0db9f6f5b9b528`, verifier green | WebKitGTK 4.1 (libwebkit2gtk-4.1) |

## Package smoke

| Platform | Status | Evidence | Package checksum |
| --- | --- | --- | --- |
| Windows x64 | PASS | CI run 29151082737 (`package-windows-x64`); jpackage app-image launched without Gradle, category `package`, evidence verifier green | — |
| macOS ARM64 | PASS | jpackage app-image `JDeskSmoke.app` executed directly; run 1783741694-0ba04a314ebd5e40, category `package`, 21/21, exit 0 | — |
| Linux x64 | PASS | CI run 29140603452 `package-linux-x64`: app-image launched without Gradle under Xvfb (category `package`) + DEB installer built (`jdesksmoke_1.0.0_amd64.deb`, sha256 2c12b580…, UNSIGNED) | — |

## Security probes (section 17.6)

| Platform | Status | Evidence |
| --- | --- | --- |
| Windows x64 | PASS | CI run 29140030589 job `security-windows-x64`, provider `windows-webview2`, artifact `security-windows-x64-evidence`, verifier green |
| macOS ARM64 | PASS | Local run 1783786286-75a7f25a7d7a6ff9 (2026-07-11 feature worktree), provider `macos-wkwebview`, 22/22, archived under `evidence/security-probe/` |
| Linux x64 | PASS | CI run 29140030589 job `security-linux-x64`, provider `linux-webkitgtk`, artifact `security-linux-x64-evidence`, verifier green |

## Stress / leak (section 17.5)

| Platform | Status | Evidence | RSS baseline |
| --- | --- | --- | --- |
| Windows x64 | PASS (10,000 IPC round trips 0 mismatch in 5152 ms; 25/25 window cycles; pending counters zero) | CI run 29137919391 | 77,111,296 -> 181,665,792 bytes (recorded, no threshold yet) |
| macOS ARM64 | PASS (10,000 IPC round trips 0 mismatch in 509 ms; 25/25 window cycles; pending counters zero) | Local run 1783741637-3a7dffd9377a2d6b | recorded in evidence environment.json (baseline only) |
| Linux x64 | PASS (10,000 IPC round trips 0 mismatch in 6208 ms; 25/25 window cycles; pending counters zero) | CI run 29139086672 | 80.9 MB -> 373.6 MB (recorded, no threshold yet) |

## Feature evidence — 2026-07-11 (macOS ARM64, local real hardware)

New capabilities verified live in native-smoke runs 1783786098-4d5f87f18232b697 (default) and
1783786141-cbdefa21a3dd2e5c (stress + stream). Windows/Linux carry the same code paths but are
compile-verified only until the next CI/hardware runs — not marked PASS here.

| Capability | Status (macOS) | Evidence case |
| --- | --- | --- |
| HTTP Range 206 partial content (`Content-Range`, sliced body, `Accept-Ranges`) | PASS | `js:asset-range-206` — `status 206 content-range=bytes 2-5/15 body="esk-"` |
| HTTP Range 416 unsatisfiable (`Content-Range: bytes */<size>`) | PASS | `js:asset-range-416` |
| Custom CSP from runtime configuration emitted on native scheme responses | PASS | `js:custom-csp-header` (DEFAULT_CSP + `media-src 'self'`) |
| Page console -> Java logging bridge (injected capture, nonce-gated) | PASS | `js:console-probe-emitted` + `java:console-bridge` |
| Window controls: focus/hide/show/minimize/maximize/fullscreen/always-on-top | PASS | `js:window-controls` |
| IPC binary streaming, 2 GiB, pull backpressure (256 KiB chunks) | PASS | `js:stream-2gb-backpressure` — 2,147,483,648 bytes / 8192 pulls / 151.6 MiB/s |
| Stream cancellation closes the token | PASS | `js:stream-cancellation` |
| Static-frontend dev loop (no Node): edit -> rebuild -> in-app reload | PASS (manual live session) | scaffolded `basic` app, `jdeskDev` log: `frontend change detected; rebuilding UI` -> `Asset change detected; reloading 1 window(s)` |

Known limits recorded honestly: the Range proof exercises `fetch` through the real WKWebView
scheme handler; a large-file `<video>` seek test with a real media fixture is still recommended.
`ClasspathAssetSource` buffers whole resources (fine for bundled UI, not for giant packaged
media — use `DirectoryAssetSource`-backed files for large media). Linux scheme responses still
buffer the requested window fully (bounded by the Range slice now, but not chunk-streamed).

## Feature evidence — 2026-07-11 batch 2 (macOS ARM64, local real hardware)

Runs 1783791506-d5c10bf0667a4010 (41/41) and 1783791575-09e1e6d35ee29915 (stress+stream,
43/43), security-probe 1783788948-01ce5170829275d3 (22/22); verifier green; archived
under `evidence/`. The same native-smoke suite also runs on the Windows and Linux CI
lanes (real WebView2 / WebKitGTK): the SecretStore probe exercises DPAPI on Windows and a
provisioned gnome-keyring on Linux, and the async-serving assertion is macOS-only (both
other platforms still serve scheme requests synchronously — documented in
`docs/guides/serving-assets.md`). The packaging lanes launch the jpackage image with
`-Djdesk.smoke.fullProbes=false`, verifying the image runs the core + console + asset
suite without provisioning a keyring/HTTP endpoint.

| Capability | Status (macOS) | Evidence case |
| --- | --- | --- |
| App-defined asset routes (`jdesk://app/proxy/...` served by Java) with 404/Range | PASS | `js:asset-route`, `js:asset-route-404`, `js:asset-route-range` |
| Async scheme serving: slow route never blocks IPC/main thread | PASS | `js:asset-route-nonblocking` — 5 IPC round trips in 5.0 ms while a 400 ms route was in flight |
| Structured command errors (`error.data`) reach the page | PASS | `js:structured-error-data` — `{"httpStatus":429,"retryAfterSeconds":30}` |
| SecretStore: real Keychain store/read/rotate/delete | PASS | `java:secret-store` |
| Automation endpoint: token-gated loopback HTTP windows/evaluate/snapshot/console | PASS | `java:automation-endpoint` — 401 without token, real PNG snapshot (442 KB), console marker retrieved |
| Window minSize clamp (user + programmatic) and remembered bounds restore | PASS | `java:window-minsize-remembered-bounds` — clamped to 400, reopened at saved 555 |

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
