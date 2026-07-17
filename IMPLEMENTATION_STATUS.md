# JDesk implementation status

This file maps the mandatory implementation phases in `JDESK_CORE_FRAMEWORK_SPEC.md` to current
evidence. Product/release state is authoritative in [STATUS.md](STATUS.md); unfinished outcome gates
are authoritative in [ROADMAP.md](ROADMAP.md).

| Spec phase | Implementation state | Current proof |
| --- | --- | --- |
| 0 — repository and research lock | Implemented | ADRs, dependency locks, wrapper and CI definitions are checked in |
| 1 — pure Java core | Verified | unit/property/architecture tests in the primary CI `core-unit-jdk25` job |
| 2 — Windows vertical slice | Verified on Windows x64 | real WebView2 native/security/package jobs and evidence artifacts |
| 3 — codegen and Gradle workflow | Verified | compiler golden tests, TypeScript tests and Gradle TestKit consumer builds |
| 4 — macOS adapter | Verified on macOS ARM64 | real WKWebView native/security/package jobs and evidence artifacts |
| 5 — Linux adapter | Verified on Linux x64 | real WebKitGTK native/security/package jobs under Xvfb |
| 6 — security hardening | Verified on primary targets | capability, navigation, CSP, IPC and asset adversarial probes |
| 7 — packaging/docs/release candidate | Partially verified | unsigned MSI/DMG/DEB pass; synchronized public release and signed installer lifecycle remain open |

Latest verified main commit: `af9a4c8`; CI
[run 29519572858](https://github.com/tuanworlddev/jdesk/actions/runs/29519572858), CodeQL
[run 29519572747](https://github.com/tuanworlddev/jdesk/actions/runs/29519572747).

The post-spec roadmap intentionally does not mark compile-only work complete. WebView sessions,
integrated packaged updates, advanced window/accessibility APIs, executable plugin loading, the E2E
SDK, secondary architectures and beta performance/API gates remain open until the cross-platform
acceptance criteria in [ROADMAP.md](ROADMAP.md) are proven.
