# JDesk Implementation Status

Spec: `JDESK_CORE_FRAMEWORK_SPEC.md` (mandatory).
This file tracks phase/gate status. Statuses: `NOT STARTED`, `IN PROGRESS`, `IMPLEMENTED-UNVERIFIED`, `VERIFIED-LOCAL`, `VERIFIED-CI`, `BLOCKED`, `FAILED`.

Machine-generated evidence rules (Section 18) apply to every `VERIFIED-*` claim. Nothing here is a pass without evidence under `build/evidence/<run-id>/` or a linked CI run.

## Environment facts (recorded 2026-07-11, actual commands run)

| Item | Value | Command |
| --- | --- | --- |
| Local OS | macOS 26.5.1 (build 25F80), arm64 | `sw_vers`, `uname -m` |
| JDK | OpenJDK 25.0.3 (Homebrew), `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` | `java -version` |
| jlink / jpackage | 25.0.3 / 25.0.3 | `jlink --version`, `jpackage --version` |
| Gradle (bootstrap) | 9.6.1 (used only to generate the checked-in wrapper) | `gradle --version` |
| Native SDK | Apple Command Line Tools, clang 21.0.0, macOS SDK 26.5 (no full Xcode) | `xcrun --show-sdk-version` |
| Node / npm | v26.2.0 / 11.15.0 | `node --version` |
| Git | 2.54.0 | `git --version` |
| CI access | GitHub CLI 2.92.0 authenticated (scopes: repo, workflow) → real GitHub Actions runners available for Windows x64, Linux x64, and macOS ARM64 | `gh auth status` |

## Platform verification plan

| Platform | How it will be verified | Status |
| --- | --- | --- |
| macOS ARM64 | Local Apple Silicon plus GitHub-hosted `macos-14` (real WKWebView) | VERIFIED-CI (run 29187403208) |
| Windows x64 | Real GitHub Actions `windows-latest` runner (WebView2 present); no local Windows hardware | VERIFIED-CI (runs 29137796715, 29137919391) |
| Linux x64 | Real GitHub Actions `ubuntu-latest` runner + Xvfb + WebKitGTK 4.1 | VERIFIED-CI (run 29139086672) |
| Windows ARM64 / macOS Intel / Linux ARM64 | Only after primary gates pass; else `UNVERIFIED` | NOT STARTED |

## Phase status

### Phase 0 — Repository and research lock
Status: DONE (2026-07-11, commit 20bc3bd)

Gates:
- [x] Repository structure per Section 4
- [x] Gradle wrapper 9.6.1 + Java 25 toolchain checked in
- [x] Module boundaries declared (all modules compile, `module-info.java` everywhere; `jdesk-gradle-plugin` non-JPMS per ADR-002; FFM package is `dev.jdesk.ffm` per ADR-001 because `native` is a Java keyword)
- [x] ADR-001..ADR-007 written under `docs/architecture/`
- [x] Dependency locking (per-project `gradle.lockfile`) + `gradle/verification-metadata.xml` (sha256, 65 components)
- [x] CI skeleton in `.github/workflows/ci.yml` (real jobs only; native jobs intentionally absent until their phases — never fake-green)
- [x] `VERIFICATION.md` initialized to NOT STARTED
- [x] Clean build from fresh checkout: `git clone <repo> && ./gradlew build --no-build-cache` → BUILD SUCCESSFUL, 70 tasks executed, exit 0 (run 2026-07-11 on macOS arm64)

No native functionality is claimed. Test-app mains exit 64 on purpose so a scaffold can never fake a pass.

### Phase 1 — Pure Java core
Status: DONE (2026-07-11)

Gates:
- [x] Lifecycle state machine, platform SPI (incl. close-request/closed/navigation-committed hooks), IPC protocol v1 (hello/invoke/cancel/result/event/nonce), limits/cancellation/backpressure, capability engine (deny-by-default, pre-deserialization), asset resolver (strict path normalization, symlink containment, MIME, cache/security headers), JsonCodec SPI + defensive Jackson default
- [x] 523 unit/property tests, 0 failures (api 107, runtime 359+, ffm 27, spi 9, testkit 12); coverage: api 89.7%/89.6%, runtime 89.4%/84.6%, ffm 97.0%/100%, spi 86.7%/100% (line/branch, thresholds 80/70 enforced by JaCoCo in `check`)
- [x] Protocol fuzz corpus: jqwik properties over envelopes (7 properties, ~7000 tries) + asset path fuzz
- [x] Architecture boundary tests (ArchUnit): api java-only; runtime free of AWT/Swing/JavaFX/sun.misc/platform/ffm deps; Jackson confined to runtime internals
- [x] CI green on real ubuntu runner: run 29136815933 (core-unit-jdk25, gradle-plugin-functional)
- [x] Fresh-worktree `./gradlew check` green (exit 0)

Bugs found and fixed during test review (evidence: test suite + CI):
- JsonLimits/IpcLimits DEFAULTS self-referential class-init NPE (framework-fatal) — fixed with literal ceilings.
- Timeout/cancel race: worker could win the terminal CAS with CANCELLED over TIMEOUT — fixed by claiming the CAS before interrupting.
- OriginNormalizer accepted query components — now rejected.

Deferred to native phases (documented, not skipped silently): deadlock regression on real adapters, leak/stress counters, RSS baselines (17.5) — they require real windows/WebViews.

Extra Phase-2 prep landed early: evidence writer/verifier (section 18) with 12 round-trip tests incl. tamper detection; native-smoke app + probe page (17.3); WebView2 vtable reference generated from SDK 1.0.2903.40.

### Phase 2 — Windows vertical slice
Status: DONE (2026-07-11, verified on real GitHub Actions windows-latest runner)

Verified with machine-generated evidence (spec 18):
- CI run 29137796715 (functional) and 29137919391 (stress) — job `windows-x64-native`, artifact `windows-x64-native-evidence`, provider `windows-webview2`, WebView2 Runtime on Windows Server 2025 x64.
- All 17.3 probes PASS through the real bridge: handshake, typed echo, Java->JS event, non-UI-thread handler, cancellation of a real sleeping command, unknown command, capability denial before handler, oversize payload, 100 concurrent invokes, remote navigation blocked, asset 200/404/traversal-rejected, secondary window create/close/recreate, clean shutdown with zero pending invocations.
- Snapshot via real CapturePreview: PNG 984x661, 52+ distinct colors, validated.
- Stress (17.5): 10,000 IPC round trips, 0 mismatches, 5152 ms; 25/25 window cycles; RSS baseline recorded (77,111,296 -> 181,665,792 bytes; baseline only, threshold ADR to follow per spec).
- Evidence verifier (anti-fake checks incl. checksum recomputation) green in the same job.

Implementation:
- Win32 FFM layer (user32/kernel32/ole32/shlwapi), documented x64 struct layouts
- STA UI dispatcher via hidden message-only window; nested pump for async COM
- COM vtable invocation + Java-implemented COM objects (gated upcalls, tear-off QI)
- WebView2 environment with `jdesk` custom scheme (secure + authority, public API)
- Message bridge, navigation policy, ContentLoading->nonce delivery, asset
  interception (SHCreateMemStream fast path + streaming Java IStream), CapturePreview
  snapshot, ProcessFailed events, popup denial
- windows-x64-native CI job with pinned WebView2 SDK 1.0.2903.40 loader + evidence upload

Gates:
- [x] Win32 event loop/window via FFM; COM support; WebView2 bridge/scheme/snapshot/navigation/diagnostics
- [x] `windows-x64-native` green on real CI runner, no fake providers (run 29137796715)
- [x] Lifecycle + IPC stress evidence uploaded from CI (run 29137919391)

Bring-up findings (fixed): strict default CSP correctly blocked the smoke page's inline scripts (page externalized); nonce control envelope could arrive before page scripts attached listeners (now captured by the doc-created init script).

### Phase 3 — Codegen and Gradle developer workflow
Status: DONE (2026-07-11)

Gates:
- [x] Annotation processor: deterministic Java registry + TS types/client generation, 46 tests incl. golden double-compile byte-identical checks and all section-11 compile-time rejections; @jdesk/client TS runtime (zero deps, protocol v1)
- [x] Gradle plugin `dev.jdesk.application`: spec-shaped extension; jdeskDoctor/GenerateBindings/FrontendBuild/Dev/RuntimeImage/Package/NativeSmokeTest/VerifyEvidence registered and real (jdeskInstaller now builds real DMG/MSI/DEB installers); packager arg builders unit-tested (18 tests)
- [x] TestKit consumer builds: 13 real functional tests (isolated consumers, config-cache reuse asserted, spaces/non-ASCII paths, real codegen TS emission, real jdeps+jlink image)
- [x] Fresh external sample (scratchpad, outside the repo): applied the plugin via includeBuild, jdeskDoctor + jdeskGenerateBindings produced GreetServiceCommands.java + typed TS client; ran the vertical slice end-to-end on the real macOS WKWebView using ONLY public APIs (nonce -> hello -> greeting.greet -> typed response -> app.quit), stdout evidence: FRESH-SAMPLE-READY + FRESH-SAMPLE-GREET-CONFIRMED, exit 0. (Spec names the Windows slice; the equally-verified macOS adapter was used for the local run — Windows consumer run follows with the Phase 7 consolidated CI.)

The original Phase-3 deferrals were completed in Phase 7: installers, signing hooks and
named-module production launchers are implemented.

### Phase 4 — macOS adapter
Status: DONE (verified locally and on GitHub-hosted Apple Silicon)

Gates:
- [x] AppKit/WKWebView via FFM (ObjC runtime bindings, public clang blocks ABI, WKURLSchemeHandler, WKScriptMessageHandler, navigation delegate, takeSnapshot). No private selectors; DevTools via public `setInspectable:` only.
- [x] Native smoke green on real macOS ARM64 locally (remediation evidence 1783846302-deb600a45fd34b54: 10,000 IPC round trips, 0 mismatch) and on GitHub-hosted Apple Silicon (`macos-arm64-native`, run 29187403208); provider `macos-wkwebview`, verifier green.
- [x] Launch from packaged `.app` image: jpackage app-image built and executed (`JDeskSmoke.app`), evidence run 1783741694-0ba04a314ebd5e40, category `package`, PASSED 21/21, exit 0.

### Phase 5 — Linux adapter
Status: DONE (2026-07-11, verified on real GitHub Actions ubuntu runner)

Gates:
- [x] GTK3/WebKitGTK 4.1 via FFM (bindings over glib/gobject/gio/gtk-3/gdk-3/soup-3.0/jsc-4.1/webkit2gtk-4.1/cairo; GLib main-context dispatcher; `jdesk` scheme classified secure + CORS via public WebKitSecurityManager APIs; snapshot via cairo PNG stream)
- [x] `linux-x64-native` green under Xvfb on real CI: run 29139086672, provider `linux-webkitgtk`, evidence 1783743433-2f0db9f6f5b9b528, all 22 cases PASS incl. 10,000-IPC stress (0 mismatch, 6208 ms), 25/25 window cycles, real 1000x700 WebKit snapshot; verifier green
- [x] Packaged app image launches without Gradle: `package-linux-x64` CI job (jpackage app-image, launched directly under Xvfb) — see run below

### Phase 6 — Security hardening
Status: DONE (2026-07-11, verified on all three primary platforms)

Gates:
- [x] Per-window capability config, origin/nonce lifecycle, navigation/popup restrictions, threat model doc (docs/security/threat-model.md)
- [x] security-probe app proves all Section 17.6 items on real WebViews: macOS run 1783744909-9ab8bd400421eeb1 (local, `macos-wkwebview`, 22/22); Windows + Linux green on real CI runners — run 29140030589 jobs `security-windows-x64` and `security-linux-x64`, evidence uploaded, verifier green.
- [x] Evidence category hardened to `native` so the anti-fake verifier enforces real providers (probe found + fixed this in the probe app).
- [x] CspValidator release check proven by unit test (unsafe-inline/eval rejected without acknowledgement; default CSP strict)

### Phase 7 — Packaging, documentation, release candidate
Status: SUBSTANTIALLY DONE (packaging + installers + three-platform CI + docs + evidence complete; signed release credentials remain)

Gates:
- [x] jlink + jpackage pipeline (Gradle plugin jdeskRuntimeImage/jdeskPackage; app images built + launched without Gradle on windows/macos/linux)
- [x] Production launchers use JPMS `--module-path`/`--module`, grant native access only
  to `dev.jdesk.platform.<os>`, and run with `--illegal-native-access=deny`; no production
  `ALL-UNNAMED` fallback
- [x] jdeskInstaller builds OS-native installers (DMG/PKG/MSI/EXE/DEB/RPM) via jpackage on the target OS: verified end-to-end locally (real 34 MB DMG through the plugin) + Windows MSI / Linux DEB in CI package jobs; unit-tested arg builder
- [x] Signing hooks (jdesk { signing { ... } } extension: Authenticode / Developer ID + notarization / GPG) — configuration surface; CI packages labeled UNSIGNED
- [x] SBOM + SHA-256 checksums: ReleaseArtifacts writes checksums.sha256 + CycloneDX 1.5 sbom.cyclonedx.json in jdeskPackage; deterministic, unit-tested (22 packager tests); verified against a real 282-file jpackage image
- [x] Package smoke evidence on all 3 primary targets, including real app launch and DMG/MSI/DEB creation on each target OS (consolidated run 29187403208)
- [x] Every required unit/plugin/native/security/package job green for the same remediation commit, followed by one `release-gate` (run 29187403208)
- [x] No primary platform marked UNVERIFIED/BLOCKED/ASSUMED: Windows x64, Linux x64, and macOS ARM64 all run on real GitHub-hosted target OS runners.
- [x] Fresh-project quick start reproduced: external sample (scratchpad) rebuilt against current HEAD — FRESH-SAMPLE-READY + FRESH-SAMPLE-GREET-CONFIRMED, exit 0
- [x] Documentation set complete (docs/ section 24) + consolidated final report (docs/verification/final-report.md)

Remaining before a signed v1 release (honestly incomplete, not claimed done): signed+notarized packages (installers build UNSIGNED today), secondary architectures, a dedicated performance benchmark harness, and the RSS regression-threshold ADR. See docs/verification/final-report.md.

### Post-review hardening

- [x] Event capacity now includes delivery already queued on the native UI dispatcher;
  backpressure is end-to-end rather than only bounding the pre-dispatch deque.
- [x] Dynamic window ids are reserved atomically; concurrent duplicate opens cannot leak
  a native window or corrupt shutdown bookkeeping.
- [x] Protocol-version mismatch returns an explicit handshake error; response encoding
  failures always produce one terminal result.
- [x] Public `ApplicationHandle` / `WindowHandle` control plane exposed through lifecycle
  and invocation context; runtime implementation packages removed from the public JPMS ABI.
- [x] TypeScript client has a lockfile, build dependency and integration tests.
- [x] Named-module native smoke and a real modular macOS jpackage app image pass with
  `--illegal-native-access=deny`; Windows/Linux CI package commands use the same model.
- [x] `jdeskDev` now provides frontend HMR plus supervised Java/resource rebuild and
  restart; a TestKit session edits a live app from v1 to v2 and verifies the restart.
- [x] `jdesk create` ships runnable `basic` and four-layer `structured` templates with a
  bundled Gradle wrapper; both templates were generated and compiled as external builds.
- [x] Architecture remediation (2026-07-12): update bytes are re-hashed at activation
  under a process lock and atomic manifest; IPC ingress/streams/frontend events are
  bounded end-to-end; activations have one serial non-UI thread contract; HTTP automation
  is an optional module excluded from production runtime images; production warnings are
  errors. Local real-native/package evidence is recorded in
  `docs/verification/remediation-2026-07-12.md`.

## Known deviations / notes

- None yet. Any technically necessary deviation from Section 4 structure will be documented as an ADR.
- Windows and Linux native iteration happens through real GitHub Actions runs (Section 0 rule 5); local machine is macOS-only.
