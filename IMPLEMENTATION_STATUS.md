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
| Windows x64 | Real GitHub Actions `windows-latest` runner (WebView2 present); no local Windows hardware | VERIFIED-CI (runs 29137796715, 29137919391) |
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
