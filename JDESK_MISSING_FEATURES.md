# JDesk missing features — independent audit

Commit audited: `81b9f1c796f91cf7bc5f89d8ec966a9b007fe72c` with a dirty worktree. This list is based on source inspection plus new runtime evidence, not historical claims.

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

- Window operations beyond basic create/show/hide/title/bounds/close: focus, minimize, maximize, fullscreen, icon, always-on-top.
- Native menu, tray, dialogs, clipboard, notifications, drag-and-drop, global shortcuts.
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
