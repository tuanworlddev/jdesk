# JDesk independent release audit

## Verdict

JDesk is **alpha**, not merely a prototype that opens a WebView: on the audited macOS host it ran a real WKWebView, transported typed Java↔JavaScript IPC, enforced capabilities/nonces/navigation, created native windows, captured real screenshots, survived 10,000 IPC round trips with no mismatches, and produced a jlink/jpackage app-image plus DMG.

It is **not beta or production-ready**. The cross-platform and installer release claims are not reproducible on the current host/current dirty worktree; most Tauri-like native APIs are absent; streaming, updater, clean-machine lifecycle, renderer recovery, comprehensive DX templates, and release-grade performance/security coverage remain missing.

## Audit identity and environment

- Commit: `81b9f1c796f91cf7bc5f89d8ec966a9b007fe72c`; worktree dirty before audit. Existing user changes were preserved.
- Test window: 2026-07-11 06:22:28–06:24:26 UTC (13:22–13:24 ICT).
- OS: macOS 26.5.1 build 25F80, Darwin 25.5.0, arm64.
- CPU/RAM: Apple M5; 25,769,803,776 bytes (24 GiB).
- JDK: Homebrew OpenJDK 25.0.3; Gradle 9.6.1; jlink/jpackage 25.0.3.
- WebView runtime: real `macos-wkwebview`/system WebKit. Exact version could not be collected and evidence reports `unknown`.
- JDK 21: unavailable; marked BLOCKED, not PASS.

## Repository discovery

The Gradle build contains API, runtime, WebView SPI, FFM support, Windows/macOS/Linux adapters, codegen, CLI, Gradle plugin, packager, testkit, three test apps, and one example. Runtime depends on API+SPI; adapters depend on SPI+FFM; codegen depends on API; plugin depends on packager. There are no Maven modules.

All three platform adapters contain native FFM implementations, not empty interfaces: Windows uses Win32/COM/WebView2, macOS uses Objective-C/AppKit/WKWebView, and Linux uses GTK3/WebKitGTK 4.1. Nevertheless, only macOS ran in this audit. Platform adapter modules have no direct test sources; compilation alone is not runtime proof.

Search found no production `UnsupportedOperationException` placeholder. Test fakes and test-only unsupported paths exist. The material absence is architectural: the public window surface only exposes show, hide, title, bounds, and close, while menu/tray/dialog/clipboard/notification/etc. have no service implementation.

## Build and developer experience

`./gradlew clean build --no-build-cache --stacktrace` succeeded in 14 seconds with 132 actionable tasks. Unit/property/TestKit checks passed, but mock/fake-based tests were not counted as native live evidence. The JS SDK separately passed 3/3 Node tests.

From an empty `/tmp` directory, both `basic` and `structured` Gradle templates were generated and compiled. Vanilla, React, Vue, and Svelte were rejected with exit 2. No Maven template exists. `jdesk build` and `jdesk bundle` were rejected as unknown commands. Hot reload and debugger attachment were not interactively demonstrated, so they remain PARTIAL.

## Live native IPC/security evidence

- Native smoke: 21/21 cases, exit 0, real 2000×1400 WKWebView screenshot.
- Stress: 10,000 round trips, zero mismatches, 530 ms aggregate; 25/25 window cycles; zero pending invocations.
- Security: 22/22 cases, including unknown/capability denial, iframe restriction, malformed JSON resilience, stale nonce rejection, error redaction, and a traversal corpus.
- Evidence checksums/manifests were independently reverified successfully.

These probes do not cover the full requested type matrix, 2 GiB streaming/backpressure, renderer crash/reload, p50/p95/p99, simultaneous multi-window event isolation, symlink escape through a real WebView, update signature attacks, or shell/file APIs. Absent APIs are NOT_IMPLEMENTED, not security PASS.

## Packaging and performance

The generated basic app produced an 82 MiB runtime image, 86 MiB app image, and unsigned 34,632,443-byte DMG. The DMG was not installed on a clean VM, signed, notarized, upgraded, or uninstalled; therefore clean-machine installer readiness is BLOCKED.

Stress RSS was 87,638,016 bytes at startup and 489,046,016 bytes after probes, a 401,408,000-byte increase. No threshold or leak attribution exists. No cold/warm startup, idle CPU, 100-cycle, 2 GiB, eight-hour soak, or process-kill recovery run was performed.

## Required final answers

- **Maturity:** alpha.
- **P0 missing:** named framework/Maven templates and CLI build/bundle; streaming/backpressure; renderer recovery; updater/signatures; clean-machine installers/upgrades; current-SHA Windows/Linux proof; single-instance/deep-link/external-browser; remaining IPC live coverage.
- **Platforms actually working:** macOS ARM64 with WKWebView is demonstrated on this worktree. Windows x64 and Linux x64 have substantial source implementations but are BLOCKED/unverified in this audit.
- **Can a new app be created without framework edits?** Yes for the Gradle `basic` and `structured` templates using the local composite build; not demonstrated from published artifacts, and not for the requested named frontend/Maven templates.
- **Installer on a clean machine?** Unknown/BLOCKED. A macOS DMG builds, but clean-machine installation was not tested.
- **IPC safe with remote/untrusted content?** Partially demonstrated for blocked navigation, iframe/capability/nonce attacks on macOS. It cannot be declared generally safe across all OSes or privileged APIs; several requested attacks are absent or untested.
- **Documented but absent?** Named frontend ecosystem support is conceptually possible but not present as CLI templates; most Tauri-like native features and updater functionality do not exist. Historical cross-platform PASS claims do not match current-worktree evidence.
- **Next work:** follow the ordered list in `JDESK_MISSING_FEATURES.md`, beginning with a clean RC and current-SHA three-OS/clean-VM verification.

Raw commands and output are under `logs/`; runtime screenshots and signed checksum manifests are under `evidence/`; detailed statuses are in `JDESK_FEATURE_MATRIX.csv`; machine results are in `JDESK_TEST_RESULTS.json` and `benchmarks/benchmark-results.json`.
