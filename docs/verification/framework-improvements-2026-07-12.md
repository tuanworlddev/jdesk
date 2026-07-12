# Framework improvements — 2026-07-12

This round turns the limitations found while building the `jdesk-notes` example into real
framework features, each verified on Windows 11 (unit tests cross-platform; native paths
runtime-driven with WebView2 150.0.4078.65). macOS/Linux native changes follow the existing
idioms and remain CI-verified.

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

### 3. WebView2 loader auto-location

`WebView2Environment` now searches next to the launcher exe, the app dir, and the working
directory for `WebView2Loader.dll` before falling back to the bare name — so a jpackage image
that ships the loader beside its `.exe` starts with no `-Djdesk.windows.webview2loader`.

- Verified: the packaged `jdesk-notes` runs standalone with the loader beside the exe.

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
  - *Interactive input to a long-lived shell.* `cmd.exe` launched in the pseudoconsole
    receives EOF on stdin and exits after its banner, so a subsequent `write()` never reaches
    it. This is a real ConPTY input-wiring gap to investigate; it was reported as a note, not
    fixed, to avoid shipping an unvalidated native change.

Run it: `./gradlew :test-apps:native-smoke:run -PjdeskPlatform=windows
-PjdeskWebView2Loader=<loader> -PjdeskMain=dev.jdesk.testapps.nativesmoke.WindowsPtyProbe`.
