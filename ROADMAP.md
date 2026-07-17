# JDesk roadmap and completion criteria

This roadmap is outcome-based. An item is complete only when its public API/tooling, adversarial
tests, documentation and real target-platform evidence all exist. A compile-only implementation is
not complete.

## 0.1.3 — public release integrity

- [ ] Maven Central, Gradle Plugin Portal, npm and GitHub publish the same version from one tag.
- [x] Release CI rejects any Gradle/JavaScript/template/tag version drift.
- [ ] A public-only canary scaffolds and builds Gradle basic, Gradle React and Maven applications.
- [x] `npm create jdesk-app@latest` works without `--jdesk-version`, a source checkout or credentials.
- [ ] README, CLI output, website and package metadata describe the actual public state.
- [x] GitHub repository description, homepage and topics are populated.

## 0.2 — production distribution and WebView sessions

- [ ] Signed/notarized MSI/EXE and DMG/PKG release artifacts are produced from protected CI.
- [ ] Disposable clean machines install, launch offline, update N to N+1, roll back a failed first
  launch, uninstall and verify bounded residue on Windows, macOS and Linux.
- [ ] `WebViewSession` supports cookies, cache clearing, proxy, user agent, downloads and persistent
  versus private storage on all primary targets.
  - [ ] Stable session ids, persistent/private storage isolation and native user-agent overrides are
    wired through WebView2, WKWebView and WebKitGTK.
    - [x] Private isolation and native user-agent overrides are wired through all three engines;
      WebView2 also supports named persistent profiles.
    - [ ] Persistent DOM storage for the custom `jdesk://` origin remains unavailable on WKWebView
      and WebKitGTK; named profiles are rejected instead of silently sharing or losing state.
  - [ ] Cookie CRUD, selective cache/site-data clearing, proxy policy and controlled downloads remain.
- [ ] Permission requests for camera, microphone, geolocation, notifications and clipboard are
  capability-gated, origin-aware and deny by default.
- [ ] Session isolation and download paths have traversal, symlink, overwrite and cancellation tests.
  - [x] A real WKWebView probe proves same-session sharing, cross-session isolation and the native
    user-agent override; API tests reject traversal-like ids and control characters.
  - [x] Real Windows/Linux probes cover their session contracts.
  - [ ] Download symlink/overwrite/cancellation probes remain.

## 0.3 — integrated updater, desktop polish and observability

- [ ] The Gradle plugin creates signed updater artifacts and manifests as part of packaging.
- [ ] Public runtime APIs expose check, staged rollout, progress, install, relaunch, health confirmation
  and rollback without requiring applications to invent a launcher protocol.
- [ ] Window APIs cover maximum size, centering, decorations, title-bar style, transparency, monitor
  enumeration, scale factor and taskbar/dock progress where supported.
- [ ] Accessibility evidence covers keyboard-only use, screen readers, high contrast, reduced motion,
  focus order, Unicode input and IME composition.
- [ ] Applications can observe renderer crash/recovery and attach redacted diagnostics or telemetry.

## 0.4 — plugin runtime and supported testing SDK

- [ ] Verified plugin jars load through an isolated module layer with compatibility ranges, explicit
  lifecycle, cleanup and capability grants.
- [ ] Plugin packaging, TypeScript bindings, create/verify/pack CLI commands and at least three official
  plugins are available from public repositories.
- [ ] JUnit and JavaScript E2E clients drive windows/input/snapshots/console with timeouts and artifacts.
- [ ] The SDK runs the same E2E suite against dev mode and a packaged application.
- [ ] Maven supports doctor, bindings, dev and package workflows or documents intentionally unsupported
  operations with equivalent commands.

## Beta / 1.0 readiness

- [ ] Spring/CDI, SQLite/JDBC and tray-utility reference applications build from public artifacts.
- [ ] Windows ARM64, macOS Intel and Linux ARM64 have native/security/package evidence.
- [ ] MSIX and at least one portable/store-friendly format per macOS/Linux are documented and tested.
- [ ] Cold/warm startup, idle CPU, peak RSS, package size, IPC latency and soak budgets are enforced per
  platform with rolling release baselines.
- [ ] Public API review is complete; experimental APIs are marked; compatibility/deprecation policy is
  enforced by CI; release/support/security policies are ready for 1.0.
