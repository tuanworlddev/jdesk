# Platform Prerequisites and Limitations

JDesk renders with each operating system's **own** WebView engine — no bundled Chromium
([ADR-003](../architecture/ADR-003-system-webviews.md)). This page lists the runtime and
build prerequisites per OS, plus the documented limitations. `jdeskDoctor` checks most of
these and reports every problem at once (see
[../development/gradle-plugin-reference.md](../development/gradle-plugin-reference.md)).

## Common (all platforms)

- **JDK 25.** Required to build and to run. Packaging additionally uses **`jlink`** and
  **`jpackage`** from the JDK ([../packaging/packaging-and-signing.md](../packaging/packaging-and-signing.md)).
- **Node.js — only if your frontend build needs it.** Vanilla HTML/JS/CSS apps (like
  `hello-vanilla`) need no Node. A Vite/React/Vue/Svelte build does.
- **FFM native access.** The platform adapters use the Foreign Function & Memory API.
  Classpath/dev launches pass `--enable-native-access=ALL-UNNAMED`; packaged runtime
  images embed it via `jlink --add-options` (see the native-access note in the plugin
  reference).

## Windows

- **Windows 10 version 1809 (build 17763) or newer**, x64. (Windows ARM64 is a secondary
  target, not yet verified.)
- **Microsoft Edge WebView2 Runtime (Evergreen).** Provided by the platform adapter
  `windows-webview2`. Modern Windows ships/updates it; if absent it must be installed.
- **`WebView2Loader.dll`** must be resolvable. In dev/CI it is supplied explicitly:
  `-PjdeskWebView2Loader=<path>` (Gradle) or `-Djdesk.windows.webview2loader=<path>`
  (JVM). CI pins WebView2 SDK **1.0.2903.40**.
- **License note.** The redistributed `WebView2Loader.dll` comes from the
  `Microsoft.Web.WebView2` package; its license is documented with the release artifacts.
  This is the only redistributed native artifact.
- Verified on the real GitHub Actions `windows-latest` runner (WebView2 on Windows Server
  2025). See [../../VERIFICATION.md](../../VERIFICATION.md).

## macOS

- **macOS 13 (Ventura) or newer.** Verified on macOS 26.5.1 arm64 (real Apple Silicon
  hardware). macOS Intel is a secondary target, not yet verified.
- **System WebKit** (WKWebView) — part of the OS, no install. Adapter `macos-wkwebview`.
- **DevTools** is available via the **public** `setInspectable:` API on **macOS 13.3+**
  only. No private selectors are used anywhere in the adapter.
- **First-thread launcher requirement.** AppKit event handling must run on the process's
  first thread, so the app is launched with **`-XstartOnFirstThread`** (added
  automatically by the `run`/`jdeskPackage` tasks on macOS).
- Building a `.app` uses `jpackage`; distribution requires Developer ID signing +
  notarization (see [../packaging/packaging-and-signing.md](../packaging/packaging-and-signing.md)).

## Linux

- **WebKitGTK 4.1** (GTK 3). Adapter `linux-webkitgtk`. Install the system package:

  ```bash
  sudo apt-get install libwebkit2gtk-4.1-0
  ```

  (Ubuntu 22.04+ / equivalent. Linux ARM64 is a secondary target, not yet verified.)
- **Headless / CI:** run under **Xvfb** — there must be a display. CI uses Xvfb on
  `ubuntu-latest`.
- **`WEBKIT_DISABLE_DMABUF_RENDERER=1`** may be needed in headless/virtualized
  environments where the DMA-BUF renderer is unavailable; set it if WebKitGTK fails to
  render under Xvfb.
- Verified on the real GitHub Actions `ubuntu-latest` runner under Xvfb with WebKitGTK
  4.1. See [../../VERIFICATION.md](../../VERIFICATION.md).

## Documented limitation: WKWebView custom-scheme secure context

WKWebView does **not** grant an arbitrary custom scheme (`jdesk://app/`) the full HTTPS
secure-context behavior a real `https://` origin gets. JDesk will **not** call private
Apple selectors to work around this. Web platform features that require a secure context
and are unavailable under the production `jdesk://app/` origin are either provided by
native plugins or documented as unsupported. See
[ADR-004](../architecture/ADR-004-no-localhost-production.md).

## Provider selection reminder

A packaged app contains exactly one platform provider. In development you choose it per OS
with `-PjdeskPlatform=<windows|macos|linux>`; zero or multiple providers is a fatal
startup error (never a silent fake). See
[../architecture/overview.md](../architecture/overview.md).
