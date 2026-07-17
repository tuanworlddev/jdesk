# JDesk status

This is the authoritative human-readable status for the current development line. A capability is
listed as verified only when the current CI matrix or a linked, reproducible live test proves it.
Historical audit snapshots are intentionally not status sources; Git history and the dated reports
under `docs/verification/` preserve them.

## Current line

- Source version: **0.1.3 (development)**.
- Stability: **pre-alpha**; public API compatibility is checked, but breaking changes remain
  possible before 1.0 under the documented support policy.
- Primary targets: Windows x64, macOS ARM64, Linux x64.
- Latest verified main commit: `af9a4c8`.
- Main CI: [run 29519572858](https://github.com/tuanworlddev/jdesk/actions/runs/29519572858).
- CodeQL: [run 29519572747](https://github.com/tuanworlddev/jdesk/actions/runs/29519572747).

## Distribution

| Surface | Current public state | Next release gate |
| --- | --- | --- |
| Maven Central | `dev.jdesk:*` 0.1.2 is public | publish every 0.1.3 module, including `jdesk-plugin` |
| Gradle Plugin Portal | 0.1.2 is not listed | `publishPlugins` must succeed and its marker POM must resolve publicly |
| npm `create-jdesk-app` | registry `latest` is 0.1.0 | publish 0.1.3 with the matching bundled CLI |
| npm `jdesk-client` | registry `latest` is 0.1.0 | publish 0.1.3 with OIDC provenance |
| GitHub Releases | latest release is 0.1.1 | create a 0.1.3 pre-release from the exact verified tag SHA |
| Native installers | unsigned MSI, DMG and DEB are CI-verified | real signing/notarization plus clean install/update/uninstall tests |

The release workflow rejects version drift among Gradle, both npm packages, the Java generator,
frontend templates and the release tag. `.github/workflows/public-canary.yml` is the release-consumer
proof: it resolves only public registries and builds clean Gradle basic, Gradle React and Maven apps.

## Verified framework baseline

The current primary-platform CI covers:

- typed Java/TypeScript commands, bidirectional events, cancellation and navigation reset;
- deny-by-default per-window capabilities, CSP/navigation hardening and native security probes;
- WebView2, WKWebView and WebKitGTK native startup, IPC, renderer recovery and snapshots;
- 2 GiB pull-streaming with backpressure and bounded binary upload routes;
- native dialogs, clipboard, notifications, tray/menu, shortcuts, deep links and file drops;
- OS secret stores, PTY backends, single-instance handoff and native packaging;
- unit, property, architecture, Gradle TestKit, CodeQL and dependency review gates.

The detailed reproducible test design lives in
[`docs/verification/native-testing-and-evidence.md`](docs/verification/native-testing-and-evidence.md).
Current live proof comes from CI artifacts, not checked-in local evidence.

## Known incomplete work

The complete acceptance criteria and ordering are tracked in [ROADMAP.md](ROADMAP.md). The major
open product surfaces are:

- public release synchronization and signed installer lifecycle;
- WebView session/network/download/permission control;
- updater integration with packaged applications;
- advanced window/monitor, accessibility and diagnostics APIs;
- executable plugin loading/tooling (the current module is the security model only);
- a supported E2E SDK, Maven tooling parity and secondary CPU architectures;
- store formats, rolling performance baselines, soak tests and pre-1.0 API freeze.

