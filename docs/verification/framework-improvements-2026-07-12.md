# Framework improvements — 2026-07-12

This round turns the limitations found while building the `jdesk-notes` example into real
framework features, each verified on Windows 11 (unit tests cross-platform; native paths
runtime-driven with WebView2 150.0.4078.65). The macOS and Linux native paths are
runtime-verified on the real CI native lanes, and the macOS menu/tray changes are additionally
live-verified on Apple Silicon (see §2). See "Verification status" at the end for the exact,
honest state — including CI lanes that have not yet re-run.

## What changed

### 1. Application directories API (`ApplicationHandle.dataDir/configDir/cacheDir`)

Apps no longer hand-roll `~/.jdesk/<id>`. New `Path dataDir()`, `configDir()`, `cacheDir()`
resolve the OS-standard locations (Windows `%APPDATA%`/`%LOCALAPPDATA%`, macOS
`~/Library/Application Support` + `~/Library/Caches`, Linux XDG), created on first access and
scoped to the application id; `-Djdesk.paths.dir=<base>` overrides all three for tests.

- Verified: `AppPathsTest` (8 cases, all three OS layouts + XDG env + override + id
  sanitization) green in `check`. The `jdesk-notes` session store now lives under the
  platform data dir.

### 2. Stateful & updatable menus (`MenuItem` checked/enabled, `TrayHandle.setMenu`)

`MenuItem.Action` gained `checked` and `enabled` (with `MenuItem.check(...)` and
`Action.checked(..)/.enabled(..)`); `TrayHandle.setMenu(MenuSpec)` (and SPI
`TrayControl.setMenu`) replaces a tray's menu so it can reflect state.

- Windows: `AppendMenuW` now ORs `MF_CHECKED`/`MF_GRAYED`; the tray menu is rebuilt on
  `setMenu`. macOS: `setState:`/`setEnabled:` on the `NSMenuItem`, `NSStatusItem` re-`setMenu:`.
  Linux: `gtk_check_menu_item_*` + `gtk_widget_set_sensitive`, tray menu ref-swapped.
- Verified: `NativeIntegrationTypesTest` covers the new API; the `jdesk-notes` tray shows
  "Start with Windows" as a checkmark that flips via `setMenu` on toggle (Windows runtime).
  macOS: live-verified on Apple Silicon via `DesktopProbe` — a checked + a disabled menu item
  drive `setState:`/`setEnabled:` and `TrayHandle.setMenu` rebuilds the `NSStatusItem` menu
  with a checked item, all applied on real AppKit without throwing while the impl's arity
  self-check still holds (`menuInstall=OK(... checked+disabled items applied without throw ...)`,
  `tray=OK(... setMenu(checked) ...)`). The click→listener dispatch and the checkmark's visual
  are GUI gestures and remain NOT auto-tested. macOS + Linux native menu/tray also pass on the
  real CI native lanes (`macos-arm64-native`, `linux-x64-native`, `security-*`).

### 3. WebView2 loader auto-location

`WebView2Environment` now searches next to the launcher exe, the app dir, and the working
directory for `WebView2Loader.dll` before falling back to the bare name — so a jpackage image
that ships the loader beside its `.exe` starts with no `-Djdesk.windows.webview2loader`.

- Verified: the packaged `jdesk-notes` runs standalone with the loader beside the exe.

### 3b. Custom tray icons on Windows (GDI+)

`WindowsShellIntegration` converts `TraySpec` PNG bytes to an `HICON` via GDI+
(`GdiplusStartup` + `GdipCreateBitmapFromStream` + `GdipCreateHICONFromBitmap`), falling back
to the default app icon on any failure — the previously-documented "no custom icon" limit is
closed (macOS/Linux already supported it). The Notes tray now shows a generated blue "N".

### 3c. Reusable vanilla bridge helper (`jdesk-bridge.js`)

A self-contained, no-bundler helper exposing `JDeskBridge.connect/invoke/onEvent`. The Notes
app now uses it instead of hand-rolled nonce/hello/invoke plumbing; any app can copy the file.

### 4. Automation docs: `/evaluate` vs `/input`

Documented that `/evaluate` runs in an isolated world (reads work; `.click()` does not fire
page listeners) and `/input` is the way to interact — the trap hit while E2E-testing the app.

### 5. `jdesk-notes` example: single-instance, drag-drop, recent files, encoding

- **Single-instance** (`builder.singleInstance`): a second launch focuses the running window
  and opens any file argument in a tab (via a `notes.openPath` page event).
- **Drag-and-drop** (`WindowHandle.onFileDrop`): dropping files on the window opens each.
- **Recent files**: most-recently-opened files persist in the session and show in the sidebar.
- **Encoding**: opening a non-UTF-8/binary file now yields a clean "Not a UTF-8 text file"
  message instead of a raw decoder error.
- **Last directory**: open/save dialogs reopen at the folder of the last file used.
- **Global shortcut + notification**: `Ctrl+Shift+N` summons the window
  (`registerGlobalShortcut`); closing to the tray posts a `showNotification` balloon —
  exercising the Win32 hotkey/`Shell_NotifyIcon` paths at runtime.

### 6. Windows ConPTY runtime probe (`WindowsPtyProbe`)

First runtime exercise of `WindowsPtyBackend` (previously compile-verified only), driving real
`cmd.exe` through `CreatePseudoConsole` + `CreateProcessW`.

- **PASS** on the process-lifecycle path: child attaches and runs, **exit code propagates
  (=7)**, **resize** does not throw, **kill** terminates the child. The exit-code path
  exercises `GetExitCodeProcess` on the live process handle, which confirms the earlier
  **double-close fix** (a corrupted handle would fail here).
- **Honest limitations found by runtime testing** (documented, not silently passed):
  - *Output rendering is timing-dependent.* The reader delivers real bytes from a live child
    (69 B from a `cmd` banner, 135 B from `ping` observed during debugging), but conhost
    renders asynchronously, so asserting exact text/bytes in a bounded window is flaky.
  - *Interactive input to a long-lived shell — deep-dived, still open.* Running the probe in a
    real console shows `cmd.exe`/`powershell.exe` stay alive (the earlier "exits immediately"
    was a gradle-no-console artifact), but the child **inherits the parent console instead of
    attaching to the pseudoconsole**, so a `write()` never reaches its stdin. Ruled out:
    handle-close ordering, buffer alignment, and COORD struct-by-value marshaling (now passed
    as a packed `JAVA_INT`, and buffers are 8-byte aligned). Instrumentation confirmed every
    `CreateProcessW` input is correct (valid HPCON, `cb`=112, attribute list set,
    `EXTENDED_STARTUPINFO_PRESENT`) and matches the Microsoft ConPTY sample, yet the attribute
    is silently ignored. Left as a documented open issue — no speculative native change shipped.
    Hardening that *was* kept (packed COORD, 8-byte alignment) passes all verified paths.

Run it: `./gradlew :test-apps:native-smoke:run -PjdeskPlatform=windows
-PjdeskWebView2Loader=<loader> -PjdeskMain=dev.jdesk.testapps.nativesmoke.WindowsPtyProbe`.

## Verification status (honest, as of this commit)

- **Full build green locally** on macOS (Apple Silicon, JDK 25): `./gradlew build` exit 0 —
  compile + all unit tests + every JaCoCo coverage gate.
- **Last CI run that actually executed jobs** was on commit `0a798e4` (run `29196303339`).
  There, the macOS and Linux native + security lanes passed — `macos-arm64-native`,
  `linux-x64-native`, `security-macos-arm64`, `security-linux-x64` all green — so the
  stateful-menu / `setMenu` / app-dirs changes are runtime-verified on real WKWebView and
  WebKitGTK, not merely compile-verified.
- **`security-windows-x64` was red** in that same run with `ILLEGAL_STATE: Event loop ended
  while waiting for init script installation` — a WebView2 **startup race**, not a probe
  assertion. It is treated as flaky pending a clean re-run (the parent commit's Windows lanes
  only went green on a re-run too), and the changed code does not touch the init-script path.
  This must be re-run and confirmed green before a release gate.
- **CI is currently blocked at the account level.** Every workflow run after `0a798e4`
  (`b559009`, `1ff7e4e`, `15065d0` — the ConPTY work) failed with **zero steps executed**
  (~2 s, all jobs), the signature of exhausted GitHub Actions minutes / a spending-limit cap —
  an infrastructure/billing state, not a code failure. **Until Actions billing is restored,
  these three commits (including the ConPTY hardening and its headless tests) are NOT
  CI-verified**, and the flaky `security-windows-x64` cannot be re-confirmed.
