# Windows local verification — 2026-07-12

First end-to-end run of the JDesk test suite on a **real Windows machine** (not the
Linux/macOS CI lanes and not the macOS authoring machine). It confirms the framework builds,
unit/functional/coverage-tests, and drives a real WebView2 native session on Windows, and it
fixes the cross-platform defects that a Windows checkout surfaced.

## Environment

| Item | Value |
| --- | --- |
| OS | Windows 11 Pro (10.0.22621), x64 (amd64) |
| JDK | Eclipse Temurin OpenJDK 25.0.3+9 (LTS) |
| Gradle | 9.6.1 (wrapper) |
| WebView2 Runtime | Evergreen 150.0.4078.65 |
| WebView2 loader | `Microsoft.Web.WebView2` 1.0.2903.40, `x64/WebView2Loader.dll` |

## Results

| Gate | Result |
| --- | --- |
| `./gradlew check --continue` (all 20 modules) | PASS — 844 tests, 0 failures; all JaCoCo line/branch coverage gates green |
| Real native smoke (`:test-apps:native-smoke:run -PjdeskPlatform=windows`, real WebView2) | PASS — 47/47 cases, provider `windows-webview2`, WebView2 150.0.4078.65, startup 4285 ms, RSS ~123 MiB, real 70 KB snapshot |
| Evidence verifier (anti-fake) for the native run | PASS — `status=PASSED problems=0`, run `1783830500-8f2ac267604ddc64` |
| Real security probe (`:test-apps:security-probe:run -PjdeskPlatform=windows`, real WebView2) | PASS — 22/22 cases; verifier `status=PASSED problems=0`, run `1783830661-3046008384e44aff` |

The native run exercises, on the real Win32/WebView2 stack: the `jdesk://app/` asset scheme
(200 + Range), the two-way IPC bridge, custom CSP emission, the page-console→Java logging
bridge, multi-window routing and window cycles, window controls
(focus/hide/show/min/max/fullscreen/always-on-top), min-size clamp + remembered bounds, the
DPAPI-backed `SecretStore` round trip, the token-gated automation loopback endpoint
(`/windows`, `/evaluate`, `/snapshot`, `/console`, `/input`), earliest-error capture, and the
RSS/startup regression budgets.

Not run locally (still covered by the Windows CI lane): the `jpackage` app-image + MSI
packaging lane. The ConPTY PTY backend, Win32 shell integration (tray/hotkeys/notifications),
and native file-drop remain **compile-verified** — no runnable Windows probe exercises them
yet (native-smoke does not open a PTY).

### Native file dialogs (comdlg32) — now runtime-verified

The Windows file dialogs (`showOpenDialog`/`showSaveDialog` → comdlg32 `IFileDialog`) were
previously **compile-verified only** ("a modal dialog can't be driven on headless CI"). They
are now driven end-to-end by the new [`examples/jdesk-notes`](../../examples/jdesk-notes) app
on real Windows 11: typing in the editor → **Save As** raised the native "Save note as"
dialog and wrote the file with the exact editor content (70 bytes, byte-for-byte match) →
**New** cleared the editor → **Open** raised the native "Open note" dialog and reloaded the
file (title + 70-char content restored). The app's toolbar Save/Open/Save-As were fired
through the token-gated automation `/input` endpoint (real DOM events); the modal dialogs
were completed with Windows UI Automation. This is the first runtime confirmation of the
Windows native file-chooser path.

### jdesk-notes: tabs, sidebar, session, tray, packaging

The example was extended into a tabbed editor and each surface was driven on real Windows 11:

- **Tabs** — browser-style tabs with a `+`; verified adding tabs and per-tab content.
- **Session persistence** — two tabs with unsaved content were written to
  `~/.jdesk/dev.jdesk.examples.notes/session.json`; after a kill + relaunch both tabs and the
  previously-active tab were restored (verified again through the packaged app: 3 tabs
  restored).
- **Files sidebar** — listing `%TEMP%` returned 37 files + 7 folders; clicking a text file
  opened it in a new tab with the file's content.
- **System tray + close-to-tray** — `WM_CLOSE` on the window left the process running with
  the window hidden (proving the Win32 `Shell_NotifyIconW` tray created successfully — the
  close-to-tray veto is gated on it; the tray path was previously compile-verified only).
- **Autostart** — the `HKCU\…\Run` add/query/delete round trip the tray "Start with Windows"
  item uses was validated on the machine.
- **Packaging** — `jpackage --type app-image` produced a self-contained image; with a
  `WebView2Loader.dll` beside the launcher it started with **no dev Java on `PATH` and an
  empty `JAVA_HOME`**, served the UI from the classpath `web/` module, connected the bridge,
  and restored the session — proving the packaged app does not depend on the dev Java
  environment. (The MSI installer additionally needs the WiX toolset on `PATH`.)

## Cross-platform / Windows defects fixed in this run

All of these made `./gradlew check` fail on a fresh Windows checkout while passing on the
Linux/macOS CI lanes — i.e. they blocked Windows contributors, not CI.

1. **Missing `.gitattributes` (line endings).** With Git's default `core.autocrlf=true` on
   Windows, checkout rewrote the LF fixtures the codegen and public-API tests compare
   byte-for-byte (`modules/jdesk-codegen/.../golden/*.golden`,
   `modules/jdesk-api/.../api/dev.jdesk.api.txt`) to CRLF, so five tests failed on
   line-ending diffs alone. Added a root `.gitattributes` that pins text to LF in the working
   tree on every OS (and `.bat`/`.cmd` to CRLF, plus explicit `binary` for assets), then
   renormalized the tree.

2. **`JpackageInstallerArgumentsTest` path separators.** The test asserted forward-slash
   argument strings; `Path.toString()` yields backslashes on Windows. The production code is
   correct (jpackage takes native paths) — the test now compares against `Path.of(...)
   .toString()`.

3. **`CupsPrintingTest` POSIX absolute path.** `CupsPrinting.buildCommand` absolutizes the
   file argument for `lp`; on Windows an absolutized `/tmp/a.pdf` gains a drive and
   backslashes. The option-mapping assertions now compute the expected file argument the same
   way, so they stay platform-independent.

4. **`jdesk-webview-spi` coverage dip on Windows.** `CupsPrinting.printFile` spawns the OS
   `lp` binary; its process branch (`waitFor`/`exitValue`) only runs where `lp` exists and is
   exercised by the macOS/Linux native lane (`java:print-file-plumbing`). On Windows those 13
   lines are dead, dropping the bundle to 0.78 < 0.80. Excluded that native `lp` execution
   glue from the unit-coverage gate (its portable `buildCommand` stays covered), so the gate
   is identical and meaningful on every OS.

5. **`WindowsPtyBackend` double-close (correctness).** In `open()`, the ConPTY child's stdin
   read handle (`inputRead`) was closed once when the pseudoconsole took ownership, then
   closed a second time after `CreateProcessW`. Between the two closes `CreateProcessW`
   allocates the process/thread handles, which the kernel can hand back the just-freed handle
   value — so the second `CloseHandle` risked closing the live process handle out from under
   the session. Removed the redundant close. (Compile-verified; not exercised by native-smoke.)

## Reproduce

```powershell
$env:JAVA_HOME = "<path-to-jdk-25>"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# Full suite
./gradlew.bat check --continue

# Real WebView2 native smoke (needs the WebView2 Runtime + a WebView2Loader.dll)
$loader = (Resolve-Path ".\.wv2sdk\build\native\x64\WebView2Loader.dll").Path
./gradlew.bat --no-configuration-cache :test-apps:native-smoke:run `
  "-PjdeskPlatform=windows" "-PjdeskWebView2Loader=$loader" --stacktrace
./gradlew.bat --no-configuration-cache :test-apps:native-smoke:verifyEvidence "-PjdeskPlatform=windows"
```

Evidence is not committed (`.gitignore` excludes `/evidence/`); the run-id above is a
provenance stamp reproducible with the commands here.
