# JDesk Notes

A real, minimal note editor built only on public JDesk APIs. It exists to exercise the
**OS-native file dialogs** end-to-end on a real desktop:

- **New** — clears the editor (frontend only).
- **Open…** — raises the native *open* dialog, reads the chosen file (UTF-8) into the editor.
- **Save** — writes straight to the current file; falls back to *Save As* when the note is
  still untitled.
- **Save As…** — raises the native *save* dialog and writes the editor content to the chosen
  path.

Open and Save&nbsp;As go through `ApplicationHandle.showOpenDialog` / `showSaveDialog`, i.e.
the OS-native, app-modal choosers: **comdlg32 `IFileDialog` on Windows**, `NSOpen/SavePanel`
on macOS, `GtkFileChooser` on Linux. All file I/O runs off the UI thread on the command's
virtual thread. Commands are the annotated `NotesService` methods, turned into compile-time
registration by `jdesk-codegen`; capabilities are deny-by-default (`notes:use` for the main
window only).

## Run

Pick the platform adapter for your OS. On Windows you also need the WebView2 Runtime plus a
`WebView2Loader.dll` (the CI fetches `Microsoft.Web.WebView2`):

```powershell
# Windows
$loader = (Resolve-Path ".\.wv2sdk\build\native\x64\WebView2Loader.dll").Path
./gradlew.bat :examples:jdesk-notes:run "-PjdeskPlatform=windows" "-PjdeskWebView2Loader=$loader"
```

```bash
# macOS / Linux
./gradlew :examples:jdesk-notes:run -PjdeskPlatform=macos    # or linux
```

Add `-PjdeskAutomation=true` to expose the token-gated automation endpoint (used to drive the
app in E2E tests; the descriptor with the port + token is written under
`build/automation/`).

## Verified

Driven end-to-end on **real Windows 11 (WebView2 150.0.4078.65)**: typing in the editor →
**Save As** raised the native "Save note as" dialog and wrote the file with the exact editor
content → **New** cleared it → **Open** raised the native "Open note" dialog and reloaded the
file. See `docs/verification/windows-local-2026-07-12.md`.

## Layout

- `src/main/java/.../Main.java` — wires the app; binds `ApplicationHandle` at `onReady`.
- `src/main/java/.../NotesService.java` — the `@DesktopCommand` handlers (`notes.open`,
  `notes.save`, `notes.saveAs`) that call the native dialogs and read/write files.
- `src/main/resources/web/` — the editor UI (vanilla HTML/CSS/JS over the JDesk bridge).
- `src/main/resources/jdesk-capabilities.json` — the `notes:use` grant.
