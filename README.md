# JDesk

A lightweight desktop application framework: **Java 25 application core + web frontend + operating-system WebView**, the Tauri development model without Rust.

- Application and plugin logic in Java (JPMS modules, virtual threads, FFM native access).
- Frontend in React, Vue, Svelte, or vanilla TypeScript — any static web build.
- System WebView (WebView2 / WKWebView / WebKitGTK), no bundled Chromium.
- Type-safe async commands and events between Java and JavaScript, generated at compile time.
- Deny-by-default capabilities, strict navigation policy, custom `jdesk://app/` asset protocol.
- `jlink` runtime image + `jpackage` native packaging.

## Honest status

**Pre-alpha, under active implementation.** See `IMPLEMENTATION_STATUS.md` for the phase-by-phase status and `VERIFICATION.md` for what has actually been verified with machine-generated evidence. Nothing in this README claims a platform works unless the verification matrix says so.

| Platform | Target | Verified |
| --- | --- | --- |
| Windows x64 (WebView2, Win 10 1809+) | v1 | ✅ real GitHub Actions runner — native + package + security + 10k-IPC stress (`VERIFICATION.md`) |
| macOS ARM64 (WKWebView, macOS 13+) | v1 | ✅ real local Apple Silicon hardware — native + package + security + stress (`VERIFICATION.md`) |
| Linux x64 (WebKitGTK 4.1, Ubuntu 22.04+) | v1 | ✅ real GitHub Actions runner (Xvfb) — native + package + security + stress (`VERIFICATION.md`) |

Verified through real system WebViews with machine-generated, checksum-validated evidence
(anti-fake, spec §18). Not yet done: signed release packages, installers (MSI/DMG/DEB),
and a macOS CI leg (macOS is verified on real local hardware). See
[docs/verification/final-report.md](docs/verification/final-report.md).

## Repository layout

- `modules/` — framework modules (`jdesk-api`, `jdesk-runtime`, platform adapters, codegen, Gradle plugin…)
- `js/jdesk-client` — TypeScript client SDK
- `examples/` — sample applications
- `test-apps/` — native smoke, security probe, packaging probe (real native runs only)
- `docs/` — architecture ADRs, development, security, verification guides

## Building

Requires JDK 25 (or lets the Gradle toolchain provision one) and the checked-in Gradle wrapper:

```bash
./gradlew build
```

## License

Apache-2.0. See `LICENSE`.
