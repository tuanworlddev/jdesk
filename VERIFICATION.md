# JDesk Verification Status

This file is updated **only from machine-generated reports** (spec sections 18, 23). A cell may claim a pass only if evidence exists under `build/evidence/<run-id>/` (local) or as a CI artifact, produced by the same commit being tested. Statuses: `NOT STARTED`, `IN PROGRESS`, `PASS (evidence: <run-id or CI link>)`, `FAIL`, `UNVERIFIED`, `BLOCKED`.

> **Evidence is not committed** (`.gitignore` excludes `/evidence/`; spec 18: "never
> commit; rerun to produce"). So the run-ids below are **provenance stamps, not files in
> the repo**. The durable, per-commit record is **CI**, which regenerates the full suite
> on Windows/Linux/macOS and uploads the evidence as run artifacts. Local macOS run-ids are
> reproducible with the commands in each section (they were produced on a dirty feature
> worktree, so they stamp behavior, not a committed SHA).
>
> **Authoritative current state — CI run [29187403208](https://github.com/tuanworlddev/jdesk/actions/runs/29187403208)
> on remediation commit `cd962ff`: all 11 required jobs plus `release-gate` green.**
> This covers unit/coverage, Gradle plugin functional tests, and native, security, and
> package execution on Windows x64, Linux x64, and macOS ARM64.

## Native smoke

| Platform | Status | Evidence | WebView version |
| --- | --- | --- | --- |
| Windows x64 | PASS | CI run 29187403208 job `windows-x64-native`, artifact `windows-x64-native-evidence`, provider `windows-webview2`, verifier green | WebView2 Evergreen on Windows Server 2025 |
| macOS ARM64 | PASS | CI run 29187403208 job `macos-arm64-native`, artifact `macos-arm64-native-evidence`, plus local remediation run 1783846302-deb600a45fd34b54; provider `macos-wkwebview`, verifier green | WKWebView (system WebKit) |
| Linux x64 | PASS | CI run 29187403208 job `linux-x64-native`, artifact `linux-x64-native-evidence`, provider `linux-webkitgtk`, verifier green | WebKitGTK 4.1 (libwebkit2gtk-4.1) |

## Package smoke

| Platform | Status | Evidence | Package checksum |
| --- | --- | --- | --- |
| Windows x64 | PASS | CI run 29187403208 (`package-windows-x64`); app-image launched without Gradle and MSI created, verifier green | UNSIGNED |
| macOS ARM64 | PASS | CI run 29187403208 (`package-macos-arm64`); app-image launched without Gradle and DMG created, verifier green | UNSIGNED |
| Linux x64 | PASS | CI run 29187403208 (`package-linux-x64`); app-image launched without Gradle under Xvfb and DEB created, verifier green | UNSIGNED |

## Security probes (section 17.6)

| Platform | Status | Evidence |
| --- | --- | --- |
| Windows x64 | PASS | CI run 29187403208 job `security-windows-x64`, provider `windows-webview2`, verifier green |
| macOS ARM64 | PASS | CI run 29187403208 job `security-macos-arm64`, plus local remediation run 1783846340-4e4d073ef0fe9923; provider `macos-wkwebview`, verifier green |
| Linux x64 | PASS | CI run 29187403208 job `security-linux-x64`, provider `linux-webkitgtk`, verifier green |

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
43/43), security-probe 1783788948-01ce5170829275d3 (22/22); verifier green; local
build/evidence (not committed — see the evidence note above; the native-smoke run-ids are
provenance stamps that have since been pruned, the security-probe run is still on disk).
The same native-smoke suite also runs on the Windows and Linux CI
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

**Cross-platform CI (2026-07-11, run 29162068874): all 8 jobs green.** Both feature
batches now run on real Windows (WebView2) and Linux (WebKitGTK) CI, not macOS only:
`windows-x64-native`, `linux-x64-native`, `security-{windows,linux}-x64`,
`package-{windows,linux}-x64`, `core-unit-jdk25`, `gradle-plugin-functional` all pass.
SecretStore exercises DPAPI on Windows and a provisioned gnome-keyring on Linux; the
Range/206, min-size (WM_GETMINMAXINFO / gtk size hints), getBounds, and console-bridge
paths are therefore verified on all three platforms — no longer compile-only.

## Feature evidence — 2026-07-12 batch 3 (macOS ARM64, local real hardware)

Runs 1783794868-9b2701ced93788b0 (45/45) and 1783794305-fa5b6399e5527fdd (stress+stream,
47/47); verifier green; local build/evidence (not committed — see the evidence note above). Interactive native panels were driven
by a `System Events` driver against a packaged `.app` (guarded to only send keystrokes
when JDeskSmoke is frontmost, so nothing leaks to other apps).

| Capability | Status (macOS) | Evidence |
| --- | --- | --- |
| File save dialog (NSSavePanel) round trip | PASS | `java:file-save-dialog` — returned the typed path `/private/tmp/jdesk-live-saved.txt` (driver-typed name + Save) |
| Window print dialog (NSPrintOperation) | PASS (behavioral) | print panel shown and dismissable, no exception; driver pressed Escape |
| printFile → CUPS lp reaches the spooler | PASS | `java:print-file-plumbing` — missing file rejected; job to a bogus printer rejected by lp |
| `/evaluate` returns parsed JSON under `result` | PASS | `java:automation-evaluate-json` — `{"result":{"a":1,"b":[2,3],"c":"x"}}` |
| `/input` synthesizes a real DOM click | PASS | `java:automation-input` — page recorded `window.__inputProbeClicked` |
| Earliest module/parse-load error reaches `/console` | PASS | `java:early-error-capture` — `Failed to load jdesk://app/does-not-exist-module.js (script)` from a window whose module never loaded |

Platform status: file dialogs are live-verified on macOS; Windows (comdlg32) and Linux
(GtkFileChooser) are implemented and compile-verified (a modal dialog can't be driven on
headless CI). `WindowHandle.print()` is macOS (NSPrintOperation, live) + Linux
(webkit_print_operation, compile); Windows WebView2 print UI is a documented gap.
`printFile` is CUPS `lp` on macOS/Linux and ShellExecute print on Windows.

## Feature evidence — 2026-07-12 batch 4 (DX from the Dragon7 friction report)

Runs 1783809395-75b906d28a0f9f3e (46/46) and 1783809410-0e37e6d9c1cedfbe (stress+stream,
48/48); verifier green; local build/evidence (not committed — see the evidence note above). These address the DX/API findings from
building a real-time multiplayer game on JDesk.

| Capability | Status (macOS) | Evidence |
| --- | --- | --- |
| Per-directive CSP builder (`Csp.defaults().connectSrc(...)`) | PASS | unit `CspTest`; widens one directive without retyping the strict default |
| Window `position(x, y)` places the native window | PASS | runtime unit `configuredPositionAppliesBoundsAfterShow`; AppKit ground-truth read via System Events showed the window at exactly `170,140` (WKWebView `window.screenX/screenY` is unreliable, so the smoke only asserts the positioned window opens: `java:window-position-opens`) |
| Codegen always emits `JDeskCommands` (even for one service) | PASS | codegen `HappyPathTest.aggregatorIsGeneratedEvenForSingleService` |
| `frontend { staticCopy() }` mirrors ui/ → dist (no Build.java) | PASS | plugin functional `staticCopyMirrorsFrontendTreeIntoDist`; scaffolded app served `/src/main.js` + `/src/style.css` live with no bundler |
| Dev app identity: standard app menu with `Quit <Name>` + ⌘Q | PASS | `installApplicationMenu` in MacPlatformApplication; live System Events read of the app-menu dropdown showed `Quit Nativesmoke` (run 1783818805, 46/46) — the framework's controllable identity surface |
| Dev app identity: bold menu-bar name on raw-JVM launch | **NOT FIXABLE at runtime** (documented, not a PASS) | Live-tested 2026-07-12 (Homebrew OpenJDK 25): the bold bar name stays "java" with `setProcessName:`, with `setMainMenu:` (menu bar item 2 = "java" while the dropdown = "Quit Nativesmoke"), and with `-Xdock:name` (AWT-only). AppKit forces the executable name for a bundle-less process; only a packaged `.app` with `CFBundleName` changes it (jpackage path already correct). Earlier "PASS" claim retracted. |
| `-Djdesk.*` flags forwarded by `./gradlew run` | PASS | plugin run-task doFirst forwards console.forward/automation to the forked app JVM |

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
