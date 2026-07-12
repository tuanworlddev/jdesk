# JDesk Notes

A real, tabbed note editor built only on public JDesk APIs. It exists to exercise a broad
slice of the framework on a real desktop: native file dialogs, a system tray, window
lifecycle, and packaging.

## Features

- **Browser-style tabs** with a `+` button; each tab is its own file/untitled buffer with a
  dirty marker. `Ctrl+T` new tab, `Ctrl+W` close, `Ctrl+Tab`/click to switch.
- **Files sidebar** (toggle with the `≡` button): open a folder via the native folder
  chooser or type a path; click a file to open it in a tab; navigate into subfolders.
- **New / Open / Save / Save As** — Open and Save As raise the OS-native, app-modal dialogs
  (`ApplicationHandle.showOpenDialog`/`showSaveDialog` → comdlg32 `IFileDialog` on Windows,
  NSOpen/SavePanel on macOS, GtkFileChooser on Linux). Save writes straight to the current
  path; untitled tabs fall back to Save As.
- **Session persistence** — open tabs (including unsaved content) are stored under
  `~/.jdesk/dev.jdesk.examples.notes/session.json` and restored on the next launch.
- **System tray** — a tray item (Show Notes / Start with Windows / Quit). Closing the window
  hides it to the tray instead of quitting, so the app keeps running and reopens on demand.
  "Start with Windows" registers the app under the current-user startup key (HKCU…\Run).

Commands are `@DesktopCommand` methods on `NotesService` (compile-time registration via
`jdesk-codegen`); capabilities are deny-by-default (`notes:use` for the main window only).
All file I/O runs off the UI thread on the command's virtual thread.

## Run (from source)

On Windows you need the WebView2 Runtime plus a `WebView2Loader.dll`:

```powershell
$loader = (Resolve-Path ".\.wv2sdk\build\native\x64\WebView2Loader.dll").Path
./gradlew.bat :examples:jdesk-notes:run "-PjdeskPlatform=windows" "-PjdeskWebView2Loader=$loader"
```

```bash
./gradlew :examples:jdesk-notes:run -PjdeskPlatform=macos    # or linux
```

Add `-PjdeskAutomation=true` for a token-gated automation endpoint (used by the E2E tests;
the descriptor with the port + token is written under `build/automation/`).

## Package a self-contained app (no system Java required)

`jpackage` bundles a trimmed JRE, so the result runs on a machine with no JDK/JAVA_HOME:

```powershell
./gradlew.bat :examples:jdesk-notes:installDist "-PjdeskPlatform=windows"
jpackage --type app-image --name JDeskNotes `
  --module-path "build\install\jdesk-notes\lib" `
  --module "dev.jdesk.examples.notes/dev.jdesk.examples.notes.Main" `
  --add-modules dev.jdesk.platform.windows `
  --java-options "--enable-native-access=dev.jdesk.platform.windows" `
  --java-options "--illegal-native-access=deny" `
  --java-options "-Djdesk.assets.module=dev.jdesk.examples.notes" `
  --dest build\jpackage
# put the WebView2 loader next to the launcher so it is found standalone
Copy-Item ..\..\.wv2sdk\build\native\x64\WebView2Loader.dll build\jpackage\JDeskNotes\
# build\jpackage\JDeskNotes\JDeskNotes.exe now runs with no dev Java on PATH
```

Add an MSI installer with `jpackage --type msi --app-image build\jpackage\JDeskNotes
--app-version 1.0.0 --win-menu --win-shortcut --dest build\installer` (needs the WiX toolset
on `PATH`).

## Verified

Driven end-to-end on **real Windows 11 (WebView2 150.0.4078.65)**: native Save/Open dialogs
(byte-for-byte file round trip), tabs + `+`, the Files sidebar (list a folder, click a file
to open a tab), session restore across a restart (unsaved tabs preserved), the system tray
(close-to-tray keeps the app running with the window hidden), the autostart registry
mechanism, and a `jpackage` app-image that launched with no dev Java on `PATH`. See
`docs/verification/windows-local-2026-07-12.md`.
