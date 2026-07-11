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
| CI access | GitHub CLI 2.92.0 authenticated (scopes: repo, workflow) → real GitHub Actions runners available for Windows x64 and Linux x64 | `gh auth status` |

## Platform verification plan

| Platform | How it will be verified | Status |
| --- | --- | --- |
| macOS ARM64 | Locally on this machine (real WKWebView) | NOT STARTED |
| Windows x64 | Real GitHub Actions `windows-latest` runner (WebView2 present); no local Windows hardware | NOT STARTED |
| Linux x64 | Real GitHub Actions `ubuntu-latest` runner + Xvfb + WebKitGTK 4.1 | NOT STARTED |
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
Status: NOT STARTED

Gates:
- [ ] Lifecycle state machine, platform SPI, IPC protocol, limits/cancellation/backpressure, capability engine, asset resolver, JsonCodec SPI
- [ ] Unit + property tests; core line coverage >= 80%, branch >= 70%
- [ ] Protocol fuzz corpus
- [ ] Architecture boundary tests (no platform deps in core)

### Phase 2 — Windows vertical slice
Status: NOT STARTED

Gates:
- [ ] Win32 event loop/window via FFM; COM support; WebView2 bridge/scheme/snapshot/navigation/diagnostics
- [ ] `windows-x64-native` green on real CI runner, no fake providers
- [ ] Lifecycle + IPC stress evidence uploaded from CI

### Phase 3 — Codegen and Gradle developer workflow
Status: NOT STARTED

Gates:
- [ ] Annotation processor; generated Java registry + TS client (deterministic, golden-tested)
- [ ] Gradle plugin `dev.jdesk.application` with required tasks
- [ ] TestKit consumer builds pass
- [ ] Fresh external sample builds and runs the vertical slice via public APIs only

### Phase 4 — macOS adapter
Status: NOT STARTED

Gates:
- [ ] AppKit/WKWebView via FFM, no private selectors
- [ ] `macos-arm64-native` green (local machine qualifies as real hardware; also CI when repo pushed)
- [ ] Launch from packaged `.app` image

### Phase 5 — Linux adapter
Status: NOT STARTED

Gates:
- [ ] GTK3/WebKitGTK 4.1 via FFM
- [ ] `linux-x64-native` green under Xvfb on real CI
- [ ] Packaged app image launches without Gradle

### Phase 6 — Security hardening
Status: NOT STARTED

Gates:
- [ ] All Section 17.6 probes pass on all primary platforms

### Phase 7 — Packaging, documentation, release candidate
Status: NOT STARTED

Gates:
- [ ] jlink + jpackage pipeline, signing hooks, SBOM/checksums
- [ ] Package smoke evidence on all 3 primary targets
- [ ] Every required CI job green for the same commit
- [ ] No `UNVERIFIED` primary platform
- [ ] Fresh-project quick start reproduced

## Known deviations / notes

- None yet. Any technically necessary deviation from Section 4 structure will be documented as an ADR.
- Windows and Linux native iteration happens through real GitHub Actions runs (Section 0 rule 5); local machine is macOS-only.
