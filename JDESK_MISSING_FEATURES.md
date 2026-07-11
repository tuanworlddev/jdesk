# JDesk missing features — independent audit

Commit audited: `81b9f1c796f91cf7bc5f89d8ec966a9b007fe72c` with a dirty worktree. This list is based on source inspection plus new runtime evidence, not historical claims.

## Resolved since the audit (2026-07-11, verified live on macOS ARM64)

Evidence: native-smoke runs `1783786098-4d5f87f18232b697` + `1783786141-cbdefa21a3dd2e5c`,
security-probe run `1783786286-75a7f25a7d7a6ff9` (archived under `evidence/`), plus the
"Feature evidence — 2026-07-11" section of `VERIFICATION.md`. Windows/Linux share the code
paths but remain compile-verified only until runs on those operating systems.

- **P0.1 (partially)**: named `vanilla`/`react`/`vue`/`svelte` templates and a `maven` template exist in `TemplateCatalog`; `basic`/`structured` are now zero-Node (no bundler, no package.json).
- **P0.3**: binary pull-streaming exists end-to-end (`BinaryStream` → `StreamManager` → `jdesk.stream.pull` → JS `invokeStream`); 2 GiB with backpressure verified live (8192 pulls, 151.6 MiB/s); stream cancellation verified; renderer reload recovery while a command is in flight verified (`reload-inflight-recovery`).
- **P0.4**: full type-matrix serialization (primitive/null/list/map/enum/nested DTO), server timeout, and simultaneous multi-window routing verified live.
- **P1 window operations**: focus, minimize, maximize, fullscreen, always-on-top implemented on all three platforms and verified live on macOS (`js:window-controls`). Window icon still missing.
- **CSP is configurable** via `JDeskApplication.Builder.contentSecurityPolicy(...)` with release screening (`CspValidator`, ack via `-Djdesk.security.acknowledgeUnsafeCsp`); custom policy verified on native responses.
- **HTTP Range/206** support in the asset pipeline (206/416/`Content-Range`/`Accept-Ranges`, seek-positioned streams) verified live through the WKWebView scheme handler.
- **Page console → Java logging bridge** (injected capture script, nonce-gated `console` envelope, `dev.jdesk.webview.console` logger; on in dev mode or `-Djdesk.console.forward=true`) verified live.
- **Static-frontend dev loop**: `jdeskDev` without `devCommand` rebuilds the UI on change and the runtime dev-mode asset watcher reloads the page — no Node required; verified in a live session on a scaffolded `basic` app.
- **Template bugs fixed**: `import "./style.css"` removed (CSS now a `<link>`, `Build.java` rewrites the path); `jdeskFrontendBuild` decoupled from `classes` (now wired to `jar`), covered by TestKit functional test.
- IPC latency instrumentation now recorded in stress runs (p50/p95/p99 in `js:ipc-stress-10000`).

### Batch 2 (2026-07-11; all three platforms green on CI run 29162068874)

- **Debug/automation channel**: opt-in loopback automation endpoint (`-Djdesk.automation=true`; token-gated `GET /windows`, `POST /evaluate`, `GET /snapshot`, `GET /console`) for E2E tests, CI, and agents — verified live over real HTTP against the running app. See `docs/guides/automation-and-e2e.md`.
- **App-defined asset routes** (`JDeskApplication.Builder.assetRoute`): Java-served binary content under `jdesk://app/<prefix>/` through the streaming pipeline (Range included) — replaces base64-over-IPC image proxying. macOS scheme serving is now fully asynchronous (background resolve/stream, main-thread-marshalled WKURLSchemeTask callbacks, stop handling); verified live that a stalled route does not block IPC. Windows/Linux still serve synchronously (documented).
- **Structured command errors**: `JDeskException(code, message, details, cause)` → `error.data` in the result envelope → `JDeskError.data` in jdesk-client; verified live.
- **SecretStore** (`ApplicationHandle.secrets()`): macOS Keychain (FFM SecItem*) verified live; Windows DPAPI and Linux secret-tool backends implemented, compile-verified; no plaintext fallback anywhere.
- **Window polish**: `WindowConfig` gains `minSize` (enforced for user and programmatic resizes), `startMaximized`, `rememberBounds` (persisted per app/window under `~/.jdesk/window-state/`); `PlatformWindow.getBounds()` added on all three platforms; verified live (clamp + restore).
- **Handler thread-model docs fixed**: handlers always ran on virtual threads, but the docs' own example pushed work onto the common ForkJoinPool via `supplyAsync` — the guide now says "just block" and shows the correct concurrent-fetch pattern.
- **Framework templates now use jdesk-client + generated TS bindings** (react/vanilla/vue/svelte) instead of a hand-rolled ~80-line bridge; `ui/src/generated/` gitignored; dev-loop state save/restore pattern documented.

### Batch 3 (2026-07-12, runs 1783793227/1783793252)

- **Native file dialogs** (`ApplicationHandle.showOpenDialog`/`showSaveDialog`): app-modal, follow the app appearance — replaces `osascript choose file`. macOS NSOpenPanel/NSSavePanel live-verified (save round trip returns the typed path); Windows comdlg32 and Linux GtkFileChooser implemented, compile-verified.
- **Printing**: `WindowHandle.print()` opens the OS print dialog for the page (macOS NSPrintOperation live; Linux webkit_print_operation compile; Windows WebView2 print UI is a documented gap). `ApplicationHandle.printFile(PrintJob)` sends a PDF straight to a printer with printer/paper/copies (macOS+Linux CUPS `lp`, live-verified reaching the spooler; Windows ShellExecute print, no copies/paper).
- **Automation `/evaluate` returns parsed JSON** under `result` (object/array/number), not just a string; new **`/input`** endpoint synthesizes DOM click/type/focus/hover/key (documented as DOM-level, not OS-level IME/hover).
- **Earliest page errors captured**: the console-capture script now installs error listeners in the capture phase at document-start, so a module/parse/resource-load failure that crashes the page before any script runs is recorded in `/console` and the Java log (verified with a deliberately broken module page).

## P0 — release blockers

1. Named starter templates for Vanilla, React, Vue, and Svelte are absent. The CLI accepts only `basic` and `structured`; Maven is absent.
2. CLI commands `jdesk build` and `jdesk bundle` are absent. Equivalent Gradle tasks exist, but that does not satisfy the advertised CLI workflow.
3. IPC lacks a file-streaming protocol, 2 GiB streaming, and demonstrated backpressure. Renderer reload/crash recovery while work is in flight is absent.
4. Full live serialization coverage is missing (primitive/null/list/map/enum/nested DTO); timeout and simultaneous multi-window routing are incomplete.
5. Updater functionality, hash/signature verification, rollback, and N-to-N+1 upgrade are absent.
6. Clean-machine installer verification is missing on all platforms. Windows WebView2 bootstrap/detection, macOS signing/notarization, and Linux WebKitGTK dependency behavior are not verified for the current SHA.
7. Single-instance enforcement, deep links, and external-browser handoff are absent.
8. Current-worktree live verification exists only for macOS. Windows and Linux code is substantial, but this audit cannot mark it PASS without runs on those operating systems.

## P1/P2 gaps

- Window operations: window icon still missing (focus/minimize/maximize/fullscreen/always-on-top/min-size/remembered-bounds are done — see Resolved).
- Native menu, tray, notifications, drag-and-drop, global shortcuts still missing (clipboard, message dialog, file dialogs, and printing are done — see Resolved).
- Cookie/session control, downloads, and uploads.
- Engine-level DevTools gating test.
- Cold/warm startup, idle CPU, p50/p95/p99 IPC latency, peak RSS, 100-window cycle, 8-hour soak, and helper/renderer recovery benchmarks.
- Exact WebView runtime version collection. Evidence currently records `unknown`.

## Documentation/implementation mismatches

- README says frontends can be React/Vue/Svelte/vanilla, but the project generator has no corresponding named templates.
- README says `jdesk create` has `basic` and `structured`, which is accurate; the broader requested DX matrix is not implemented.
- Historical verification documents claim all three primary platforms and installers. Those runs are from other commits or CI artifacts and do not prove this dirty current worktree.
- Security probe labels DevTools disabled, but its own detail states that this is config-level only and engine-level verification is manual.

## Recommended order

1. Freeze a clean release candidate commit and rerun current-SHA native/security/package tests on macOS, Windows, and Linux.
2. Build disposable clean-machine installer lanes, including offline launch, uninstall residue, and N-to-N+1 upgrade.
3. Implement streaming/backpressure and renderer recovery; add adversarial and 2 GiB live tests.
4. Complete IPC type, timeout, concurrent, multi-window routing, and latency instrumentation.
5. Decide the supported DX contract; implement named templates/Maven/CLI commands or narrow public claims.
6. Implement updater signing/hash verification and release signing/notarization.
7. Add missing window/native services in user-value order.
8. Establish performance thresholds and scheduled soak/crash-recovery testing.
