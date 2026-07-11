# JDesk remediation progress

Implemented and verified on macOS ARM64, 2026-07-11, dirty worktree based on commit `81b9f1c796f91cf7bc5f89d8ec966a9b007fe72c`.

## Completed

- CLI templates `vanilla`, `react`, `vue`, and `svelte`. Each was generated in an empty directory, installed with npm, built by Vite, and compiled by Gradle.
- CLI `jdesk build` delegates to the project wrapper and preserves its exit code.
- CLI `jdesk bundle` delegates to `jdeskInstaller`; live verification produced a macOS DMG.
- Native IPC type matrix now covers primitive, null, list, map, enum, record, and nested DTO through real WKWebView.
- Native server timeout is tested separately from cancellation and returned `TIMEOUT`.
- IPC stress now records throughput and p50/p95/p99. Latest run: 10,000 requests, zero mismatches, 503.0 ms, 19,880.7 req/s, p50 3 ms, p95 8 ms, p99 17 ms.
- Window stress expanded from 25 to 100 create/destroy cycles; latest live run completed 100/100.
- Renderer/process failure notifications are now part of the platform SPI. All three
  adapters expose their existing native failure signals; runtime invalidates the dead
  session and reloads the configured entry document. Contract test passes.
- Live in-flight reload recovery passes on WKWebView with a fresh nonce and zero pending
  invocations (`evidence/remediation-reload-2/1783751780-2ce4ab9891cf903a`).
- Live simultaneous multi-window routing isolation passes (`left=left`, `right=right`) in
  `evidence/remediation-routing-2/1783751849-755f18b9d33609f7`.
- macOS diagnostics now records the actual system framework version. Latest evidence
  reports `WebKit 21624 (21624.2.5.11.4)` instead of `unknown`.
- Consolidated stress evidence `evidence/remediation-final/1783751891-4d1ed41ec8b3e7ef`
  passes 10,000 IPC calls, isolated simultaneous windows, and 100/100 window cycles.
- Pull-based `BinaryStream` support is implemented with 256 KiB maximum demand chunks,
  navigation-scoped random tokens, cancellation, and automatic close on navigation/window
  shutdown. Live WKWebView evidence transferred 2,147,483,648 bytes in 8,192 pulls at
  152.5 MiB/s and verified cancellation in
  `evidence/remediation-stream-2gb-2/1783752156-4faacdfb920b8423`.
- A Maven starter now resolves published JDesk modules, selects the OS adapter through
  Maven profiles, runs the annotation processor, and compiles as a named module. A clean
  generated consumer passed `mvn -B clean package` with Maven 3.9.16/JDK 25.
- New `jdesk-updater` module verifies update packages in a bounded streaming pass using
  SHA-256 and Ed25519. Tests reject wrong hash, wrong signing key/signature, tampering,
  oversized files, and symlink packages; authentic packages pass.
- Updater N→N+1 transaction stages immutable version directories, atomically switches
  the current pointer, retains the previous version, and rolls back without modifying
  either payload. Traversal version names and symlink roots are rejected.
- Public `WindowHandle` now controls show/hide/focus/title/bounds/minimize/maximize/
  fullscreen/always-on-top through the UI dispatcher. Native implementations compile
  for AppKit, Win32, and GTK; the full sequence passed live on AppKit in
  `evidence/remediation-window-controls/1783752763-072a9dbf4e4aaabf`.
- Bidirectional frontend events and simultaneous multi-window routing isolation are live
  verified on WKWebView.
- Engine-level production DevTools gating is queried from WKWebView and passed in
  `evidence/remediation-devtools-2/1783753618-9575fe43d9e0f9b7`.
- Single-instance coordination is integrated into `JDeskApplication` as an opt-in lifecycle
  feature. Secondary launches deliver bounded arguments/deep-link URIs over authenticated
  loopback IPC; runtime retains and closes the primary lock/session.
- The macOS DMG checksum verifies, mounts read-only, and its copied `.app` runs the complete
  native smoke suite successfully after the DMG is detached using its bundled runtime
  (`build/evidence/1783753785-c908e823bb56b9d0`). The app is ad-hoc signed only; this is
  local packaging evidence, not a clean-VM/notarization PASS.
- A real WKWebView WebContent process was terminated (`23302`, SIGTERM); WebKit created
  replacement PID `23336`, runtime observed `RENDER_PROCESS_EXITED`, reloaded, and the full
  smoke suite finished with a valid snapshot and zero pending invocations in
  `evidence/remediation-process-kill/1783753855-13f74bbcc519c8c1`.
- Typed native message dialogs are implemented end-to-end through `ApplicationHandle` and
  all three adapters (`NSAlert`, `MessageBoxW`, `GtkMessageDialog`). Runtime bounds dialog
  text and marshals calls to the UI thread. The AppKit alert returned its real button result
  in `evidence/remediation-message-dialog/1783754122-daa9882694158a78`; because Accessibility
  automation was denied (`-25211`) and the terminal interrupt coincided with dismissal, this
  evidence remains PARTIAL rather than a clean automated interaction PASS.

Evidence: `evidence/remediation-ipc/1783751537-185094132ef1b21d` and `evidence/remediation-stress/1783751545-0a09064617646504`.

## Still open

- Clean-VM installer lifecycle and platform installer replacement orchestration.
- Missing advanced window/native services.
- Windows/Linux live verification, signing/notarization, soak and crash-recovery runs.
