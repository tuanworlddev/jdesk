# JDesk status

This is the authoritative human-readable status for the current development line. A capability is
listed as verified only when the current CI matrix or a linked, reproducible live test proves it.
Historical audit snapshots are intentionally not status sources; Git history and the dated reports
under `docs/verification/` preserve them.

## Current line

- Source version: **0.1.3 (released pre-alpha)**.
- Stability: **pre-alpha**; public API compatibility is checked, but breaking changes remain
  possible before 1.0 under the documented support policy.
- Primary targets: Windows x64, macOS ARM64, Linux x64.
- Latest verified main commit: `20ebb07`.
- Main CI: [run 29549789193](https://github.com/tuanworlddev/jdesk/actions/runs/29549789193).
- CodeQL: [run 29549789253](https://github.com/tuanworlddev/jdesk/actions/runs/29549789253).

## Distribution

| Surface | Current public state | Next release gate |
| --- | --- | --- |
| Maven Central | every `dev.jdesk:*` 0.1.3 module is public, including `jdesk-plugin` | public canary build |
| Gradle Plugin Portal | 0.1.3 submitted successfully; first-plugin manual approval is pending | marker POM must resolve publicly after Gradle approval |
| npm `create-jdesk-app` | `latest` is 0.1.3 with provenance; live default scaffold passed | public Gradle builds after Plugin Portal approval |
| npm `jdesk-client` | `latest` is 0.1.3 with GitHub OIDC provenance | public canary build |
| GitHub Releases | [0.1.3 pre-release](https://github.com/tuanworlddev/jdesk/releases/tag/v0.1.3) with JARs/checksums | enable optional attestations when repository policy is ready |
| Native installers | unsigned MSI, DMG and DEB are CI-verified | real signing/notarization plus clean install/update/uninstall tests |

The release workflow rejects version drift among Gradle, both npm packages, the Java generator,
frontend templates and the release tag. `.github/workflows/public-canary.yml` is the release-consumer
proof: it resolves only public registries and builds clean Gradle basic, Gradle React and Maven apps.
Release [run 29548770955](https://github.com/tuanworlddev/jdesk/actions/runs/29548770955)
published Maven, Plugin Portal submission, npm and GitHub from `v0.1.3`. Public-canary
[run 29548931570](https://github.com/tuanworlddev/jdesk/actions/runs/29548931570) proved npm was
immediate and Maven propagated, then failed honestly because Gradle reports the new plugin is still
awaiting first-submission approval. Rerun it unchanged after the marker becomes public.

## Verified framework baseline

The current primary-platform CI covers:

- typed Java/TypeScript commands, bidirectional events, cancellation and navigation reset;
- deny-by-default per-window capabilities, CSP/navigation hardening and native security probes;
- WebView2, WKWebView and WebKitGTK native startup, IPC, renderer recovery and snapshots;
- 2 GiB pull-streaming with backpressure and bounded binary upload routes;
- native dialogs, clipboard, notifications, tray/menu, shortcuts, deep links and file drops;
- OS secret stores, PTY backends, single-instance handoff and native packaging;
- unit, property, architecture, Gradle TestKit, CodeQL and dependency review gates.

The current codebase also contains the first `WebViewSession` slice: stable session ids,
private storage isolation and native user-agent overrides on all three adapters, plus named
persistent profiles on Windows. A real WKWebView run proves same-session `localStorage`
sharing, isolation between private sessions, the exact user-agent override and fail-fast handling
for unsupported named persistence; CI repeats the contract probes on WebView2 and WebKitGTK.
Durable `jdesk://` DOM storage on WKWebView/WebKitGTK and
cookie/cache/proxy/download/permission controls are still incomplete, so the roadmap item remains
open.

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
