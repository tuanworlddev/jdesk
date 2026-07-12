# JDesk missing features — independent audit

Commit audited: `81b9f1c796f91cf7bc5f89d8ec966a9b007fe72c` with a dirty worktree. This list is based on source inspection plus new runtime evidence, not historical claims.

## Resolved since the audit (2026-07-11, verified live on macOS ARM64)

Evidence: native-smoke runs `1783786098-4d5f87f18232b697` + `1783786141-cbdefa21a3dd2e5c`,
security-probe run `1783786286-75a7f25a7d7a6ff9` (local `build/evidence/`, **not committed** —
`.gitignore` excludes `/evidence/`; the run-ids are provenance stamps, not files in the repo,
and the durable per-commit record is CI — see the note at the top of `VERIFICATION.md`), plus
the "Feature evidence — 2026-07-11" section of `VERIFICATION.md`. Windows/Linux share the code
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
- **Binary upload channel** (POST body → asset route, GAP-002): a page `fetch(url, {method:'POST', body})` reaches a route as raw bytes via `AssetRoute.Request.body()`, no base64, capped by `jdesk.assets.maxUploadBytes` (413 beyond it; 405 for methods outside GET/HEAD/POST). **macOS live-verified** byte-exact (2 MiB POST SHA-256 match, 5 MiB→413 reading only cap+1, PUT→405, ranged GET→206) via `test-apps/native-smoke` `UploadProbe`. Windows/Linux adapters do not forward the request body yet (PLATFORM-001).
- **File watching** (`ApplicationHandle.watchFiles`, GAP-001): coalesced change batches off the UI thread; `FileWatchEvent`/`FileWatchOptions`/`FileWatchHandle`. **macOS** uses FSEvents via FFM — **live-verified** event-driven at ~10–13 ms for create/modify/delete incl. Unicode paths and recursive subtrees (`WatchProbe`); Windows/Linux use the recursive `WatchService` backend (the same portable backend is the ~2 s-polling macOS fallback). Kind is best-effort (`DELETED` existence-checked; FSEvents cumulative flags blur create/modify).
- **Desktop integration** (GAP-004, 4/10): `systemTheme()` (light/dark), binary clipboard (`readClipboard`/`writeClipboard` by UTI, 64 MiB cap), `setDockBadge`, `setApplicationMenu` (`MenuSpec`/`MenuItem`, accelerators). **macOS live-verified** — theme cross-checked vs `AppleInterfaceStyle`, clipboard SHA-256 round-trip, dock badge set+clear, menu install structurally self-checked (2 top items) (`DesktopProbe`). Menu *click* dispatch and remaining GAP-004 (context menu, tray, global shortcut, notification, app icon, file-drop) not done/auto-tested — mostly GUI-interaction gated.
- **Pseudo-terminals** (`ApplicationHandle.openPty`, GAP-003): real PTY child processes; `PtySpec`/`PtyHandle` (write/resize/exitCode/terminate/kill). **macOS** uses `openpty` + `posix_spawnp` (no `fork`) with `POSIX_SPAWN_SETSID` + controlling TTY — **live-verified** on `/bin/sh`: real `/dev/ttys000`, `stty size` 24×80→40×100 after resize, `exit 7`→exitCode 7, group-kill reaps a backgrounded child (no orphan) via `PtyProbe`. Windows (ConPTY)/Linux (openpty) not implemented → `ILLEGAL_STATE` (PLATFORM-002).
- **Structured command errors**: `JDeskException(code, message, details, cause)` → `error.data` in the result envelope → `JDeskError.data` in jdesk-client; verified live.
- **SecretStore** (`ApplicationHandle.secrets()`): macOS Keychain (FFM SecItem*) verified live; Windows DPAPI and Linux secret-tool backends implemented, compile-verified; no plaintext fallback anywhere.
- **Window polish**: `WindowConfig` gains `minSize` (enforced for user and programmatic resizes), `startMaximized`, `rememberBounds` (persisted per app/window under `~/.jdesk/window-state/`); `PlatformWindow.getBounds()` added on all three platforms; verified live (clamp + restore).
- **Handler thread-model docs fixed**: handlers always ran on virtual threads, but the docs' own example pushed work onto the common ForkJoinPool via `supplyAsync` — the guide now says "just block" and shows the correct concurrent-fetch pattern.
- **Framework templates now use jdesk-client + generated TS bindings** (react/vanilla/vue/svelte) instead of a hand-rolled ~80-line bridge; `ui/src/generated/` gitignored; dev-loop state save/restore pattern documented.

### Batch 3 (2026-07-12; macOS native-smoke run 1783794305 + CI run 29162068874)

- **Native file dialogs** (`ApplicationHandle.showOpenDialog`/`showSaveDialog`): app-modal, follow the app appearance — replaces `osascript choose file`. macOS NSOpenPanel/NSSavePanel live-verified (save round trip returns the typed path); Windows comdlg32 and Linux GtkFileChooser implemented, compile-verified.
- **Printing**: `WindowHandle.print()` opens the OS print dialog for the page (macOS NSPrintOperation live; Linux webkit_print_operation compile; Windows WebView2 print UI is a documented gap). `ApplicationHandle.printFile(PrintJob)` sends a PDF straight to a printer with printer/paper/copies (macOS+Linux CUPS `lp`, live-verified reaching the spooler; Windows ShellExecute print, no copies/paper).
- **Automation `/evaluate` returns parsed JSON** under `result` (object/array/number), not just a string; new **`/input`** endpoint synthesizes DOM click/type/focus/hover/key (documented as DOM-level, not OS-level IME/hover).
- **Earliest page errors captured**: the console-capture script now installs error listeners in the capture phase at document-start, so a module/parse/resource-load failure that crashes the page before any script runs is recorded in `/console` and the Java log (verified with a deliberately broken module page).

### Batch 4 (2026-07-12, DX from the Dragon7 real-time-game friction report)

- **Per-directive CSP builder** (`Csp.defaults().connectSrc(...).imgSrc(...)`): widen one directive without retyping (and risk weakening) the whole policy. `Builder.contentSecurityPolicy(Csp)` overload; validator unchanged.
- **`WindowConfig.position(x, y)`**: place windows at an explicit top-left (e.g. two instances side by side for multiplayer testing); applied after show() so the native frame sticks. Verified natively at exactly 170,140 via System Events.
- **Codegen always emits `JDeskCommands`**: previously only when a package had 2+ services, so a one-service app copied from a multi-service template failed with `cannot find symbol JDeskCommands`. Now emitted once per package with any service.
- **`frontend { staticCopy() }`**: no-bundler apps drop `Build.java` — the frontend build recursively copies `ui/` → `dist/` (preserving structure so `/src/...` resolves), on both the production build and the dev-loop rebuild. Templates updated.
- **Dev app identity** (macOS): JDesk now installs a standard application menu with a `Quit <Name>` item + working ⌘Q (a raw FFM `NSApplication` previously had only AppKit's bare auto-menu), and sets `NSProcessInfo.processName`. **Honest limitation, verified live (2026-07-12, Homebrew OpenJDK 25):** on a non-bundled `gradlew run` the *bold application name* in the menu bar stays **"java"** — AppKit derives it from the executable name for a bundle-less process and ignores both the process name and the first menu item's title. Three approaches were live-tested and all confirmed ineffective for the bold name: `NSProcessInfo setProcessName:`, an explicit `setMainMenu:` with the app name as the first item's title (menu bar item 2 still reads "java" while the dropdown correctly shows "Quit Nativesmoke"), and the launcher flag `-Xdock:name` (only renames AWT apps). The **only** fix is a real `.app` carrying `CFBundleName`, which `jpackage` already produces — so **packaged apps show the correct name; dev `gradlew run` does not**. The earlier claim that `setProcessName:` fixed the menu-bar name was wrong and is retracted here. TCC/permission dialogs likewise read the code-signature/bundle identity, not the process name. **Window icon is still a gap.**
- **Debug-flag discoverability + forwarding**: `./gradlew run` forwards any `-Djdesk.*` flag to the app, and the README quickstart + template `build.gradle.kts` now surface `-Djdesk.console.forward` and `-Djdesk.automation`. New guide `networked-and-realtime-apps.md` covers CSP `connect-src`, choosing a WS/HTTP lib, when NOT to use IPC, Web Audio, and multi-instance running.
- **Cross-platform**: the friction report's "only macOS verified" caveat is now partly resolved — the full native-smoke + security + package suite runs on Windows (WebView2) and Linux (WebKitGTK) CI. Real-time specifics (autoplay/secure-context per engine) still merit per-engine attention.

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
- Native menu, tray, notifications, drag-and-drop, global shortcuts, application icon still missing (GAP-004 remainder; several are GUI-interaction gated). System theme, binary clipboard, and Dock badge are done on macOS — see Resolved.
- Cookie/session control and downloads. (Binary upload via POST asset routes is done on macOS — see Resolved; Windows/Linux body forwarding is PLATFORM-001.)
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
