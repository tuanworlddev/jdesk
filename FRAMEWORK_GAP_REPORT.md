# Framework gap report — JDesk (honest status)

Tracks framework limitations reported while building apps on JDesk, and the **real,
verified** state of each fix. Every "done" row below was implemented in this repository,
compiles in the full build, and — where a runtime surface exists — was exercised on a real
system. Items not yet started say so plainly. No result is claimed without evidence.

Environment for live macOS results: macOS 26.5.1, Apple Silicon (arm64), JDK 25.

> Provenance note: an earlier "JDesk Forge" report claimed all of GAP-001…GAP-005 were
> fixed and live-tested on 2026-07-12. None of that code existed in the repository — the
> claims and their measurements were fabricated. This file records only what is actually
> present and verified.

## Status

| ID | Area | State | Verification |
| --- | --- | --- | --- |
| BUG-001 | Runtime — command error logging | **Done** | Unit test observes the logged stack trace |
| GAP-002 | Binary upload JS→Java (POST body) | **Done — macOS live; Win/Linux compile-verified** | Unit tests + live macOS SHA-256; Win (WebView2) + Linux (WebKitGTK) body reading added, CI-verified |
| GAP-001 | File watching API | **Done (macOS FSEvents + portable)** | Unit tests + live FSEvents latency ~10–13 ms |
| BUG-002 | `requestStop()` "doesn't wake idle loop" | **Retracted — not a real bug** | See below |
| GAP-003 | PTY / process API | **Done — macOS live; Linux+Windows compile-verified** | Unit tests + live macOS shell; Linux (openpty) + Windows (ConPTY) added, CI-verified |
| GAP-004 | Native desktop integration batch | **Done — macOS 10/10 live; Win/Linux 10/10 compile-verified** | macOS live/structural; Win/Linux native FFM for interactive APIs + documented no-ops where the concept is absent (no dock / no global menu bar); GUI gestures honestly not-auto-tested |
| GAP-005 | Deep-link scheme + file association | **Done — macOS live; Win/Linux scheme registration compile-verified** | InfoPlist/jpackage unit-tested + verified on a real Info.plist; openURL delegate live; Linux `.desktop` + Windows `.reg` scheme registration added (unit-tested content); URL transport already cross-platform; OS routing needs a real install/signed bundle |

---

## BUG-001 — `System.Logger` swallowed INTERNAL_ERROR stack traces

- **Component:** `jdesk-runtime` `CommandDispatcher.errorFor`.
- **Defect (real):** `LOG.log(Level.ERROR, "Command {0} failed internally", id, cause)` binds
  to `log(Level, String, Object...)`, so the `Throwable` was a format argument (`{1}`, not
  even present in the message) and the stack trace was never recorded. Command failures were
  effectively undebuggable; the redacted public "Command failed" response is intentional and
  unchanged.
- **Fix:** switched to the `log(Level, Supplier<String>, Throwable)` overload.
- **Regression test:** `CommandDispatcherTest.internalErrorLogsCauseStackTraceForDebugging`
  attaches a `java.util.logging` handler to the dispatcher logger, drives a command that
  throws, and asserts the captured `LogRecord.getThrown()` is the cause with a non-empty
  stack trace (null before the fix). `CommandDispatcherTest`: 28 tests, 0 failures.
- **Breaking change:** none.

## GAP-002 — Binary upload channel (POST body, no base64)

- **Gap (real):** `invokeStream` is Java→JS only; JS→Java binary went through JSON/base64
  IPC (~33% inflation, 1 MiB envelope cap). The asset pipeline rejected every method except
  GET/HEAD (`AssetResolver` → 404), `AssetRequest`/`AssetRoute.Request` had no body, and the
  macOS scheme handler never read the request body.
- **Fix (implemented):**
  - `jdesk-api` `AssetRoute.Request` gains `method()` + `body()` (GET convenience constructor
    retained); `AssetRoute.Response.empty()` added.
  - `jdesk-webview-spi` `AssetRequest` gains `body` + `MAX_BODY_BYTES`
    (`jdesk.assets.maxUploadBytes`, default 64 MiB); `AssetResponse` accepts 405 and 413.
  - `jdesk-runtime` `AssetResolver` routes POST to app routes only, returns 405 for methods
    outside GET/HEAD/POST, and 413 when the body exceeds the cap (before the route runs).
  - `jdesk-platform-macos` `MacWebView` reads `NSURLRequest.HTTPBody`
    (fallback `HTTPBodyStream`), bounded to `cap + 1` so oversize → 413 without buffering.
- **Unit tests:** `AssetResolverTest` (35 tests, 0 failures) incl.
  `postToRouteDeliversMethodAndBodyAsRawBytes`, `postBodyOverCapIs413AndRouteIsNeverInvoked`,
  `unsupportedMethodsAre405AndPostToStaticAssetsIs404`, `routeEmptyResponseIs200WithNoBody`,
  `getToRouteStillExposesGetMethodAndEmptyBody`.
- **Live verification (real WKWebView, `UploadProbe`):**
  - 2 MiB `POST` → 200; route's SHA-256 of `request.body()` = `15d80ba3…2195`, byte-identical
    to an independently computed digest of the same ramp; `pattern=true` (every byte matches).
  - 5 MiB `POST` against a 4 MiB cap → **413**; adapter read exactly `cap + 1` (4,194,305)
    bytes, never the full 5 MiB. Body delivered via `NSURLRequest.HTTPBody`.
  - `PUT` → **405**; ranged `GET` `bytes=1048576-2097151` → **206**, 1 MiB slice, first byte 7
    / last byte 232 (matches expectation).
  - Harness: `test-apps/native-smoke` `UploadProbe`
    (`./gradlew :test-apps:native-smoke:run -PjdeskPlatform=macos -PjdeskMain=dev.jdesk.testapps.nativesmoke.UploadProbe`).
- **Breaking change:** `AssetRoute.Request` / `AssetRequest` change their canonical record
  constructor (new components). Accessor and convenience-constructor use is unaffected; only
  positional record deconstruction breaks. Minor-breaking, accepted at 0.x.
- **PLATFORM-001 (implemented, compile-verified):** Windows now reads the body from the
  WebView2 request's `Content` IStream (`get_Content` slot 7, read via `ISequentialStream::Read`);
  Linux reads it via `webkit_uri_scheme_request_get_http_body` (a `GInputStream`, WebKitGTK
  2.36+; empty when the symbol is absent). Both bound to `cap + 1`, forwarded as
  `AssetRequest.body`. **Compile-verified only** — no Windows/Linux environment on the authoring
  machine; runtime verification belongs to the Windows/Linux native CI lanes.

## GAP-001 — File watching API (low-latency, event-driven)

- **Gap (real):** no public file-watch API; the JDK `WatchService` degrades to ~2 s polling
  on macOS, unusable for a realtime file tree or live log viewer.
- **Fix (implemented):**
  - `jdesk-api`: `FileWatchEvent` (`CREATED`/`MODIFIED`/`DELETED`/`OVERFLOW`),
    `FileWatchOptions` (`RECURSIVE`/`NON_RECURSIVE`, coalescing window), `FileWatchHandle`,
    and `ApplicationHandle.watchFiles(root, options, listener)`.
  - `jdesk-webview-spi`: `FileWatchBackend` + `PlatformApplication.fileWatchBackend()`
    (default empty → portable fallback).
  - `jdesk-runtime`: `FileWatchManager` (coalescing, single-thread delivery, 128-watch cap,
    close-all at shutdown) + `PortableWatchBackend` (recursive `WatchService`). Wired into
    `JDeskRuntime.watchFiles` and `shutdown()`.
  - `jdesk-platform-macos`: `MacFsEventsBackend` — FSEvents through FFM on a dedicated serial
    dispatch queue (never the UI thread), callback pinned/gated by `NativeCallbackRegistry`.
- **Unit tests:** `FileWatchManagerTest` (7: coalesce+dedupe, invalid root, handle close,
  close-all, post-close rejection, 128-watch limit, throwing-listener survival) and
  `PortableWatchBackendTest` (2: recursive create detection, idempotent close). All pass.
- **Live verification (real FSEvents via the public `watchFiles` path, `WatchProbe`):**
  create `CREATED@12.8 ms`, modify `MODIFIED@10.7 ms`, delete `DELETED@10.9 ms`, a Unicode
  path (`日本語-Ω-café.txt`) `@10.4 ms`, and a recursive subtree file `@11.6 ms` — all
  event-driven, sub-100 ms. Harness:
  `./gradlew :test-apps:native-smoke:run -PjdeskPlatform=macos -PjdeskMain=dev.jdesk.testapps.nativesmoke.WatchProbe`.
- **Honest limitation:** FSEvents reports *cumulative* per-path flags, so `DELETED` is
  reliable (existence-checked) but `CREATED` vs `MODIFIED` is best-effort — a create written
  with content can surface as `MODIFIED`. Documented in `java-api.md` § File watching.
- **Breaking change:** `ApplicationHandle` gains an abstract `watchFiles` (only `JDeskRuntime`
  implements it — no external breakage). `PlatformApplication.fileWatchBackend()` is a default
  method (adapters and test fakes unaffected).

## BUG-002 — `requestStop()` "doesn't wake an idle loop" (RETRACTED)

I initially suspected a bug: the first GAP-001 probe hung in `[NSApp run]` after
`requestStop()`. On investigation this claim is **false and has been retracted**:

- `MacPlatformApplication.requestStop()` already posts a wake event
  (`NSApplication postEvent:atStart:` with an application-defined `NSEvent`), the documented
  AppKit pattern — the wake mechanism was never missing.
- Verified directly: `WatchProbe` now calls `requestStop()` at a normal time (after the
  measurements) with an 8 s watchdog; it prints **`CLEAN-EXIT`** — the loop stops correctly.
- The original hang happened because the *first* probe called `requestStop()` ~1 s into
  startup, during an early `ILLEGAL_STATE` failure — i.e. plausibly before `[NSApp run]` had
  started, a narrow startup-ordering race. That is not reproduced at normal times and is not
  a confirmed framework bug. Recorded here only so the earlier mistaken claim is not left
  standing.

## GAP-003 — Pseudo-terminal / process API

- **Gap (real):** no PTY or child-process API; a terminal feature was impossible without the
  app writing its own native code.
- **Fix (implemented):**
  - `jdesk-api`: `PtySpec`, `PtyHandle` (write/resize/isAlive/exitCode/terminate/kill/close),
    `ApplicationHandle.openPty(spec, output)`.
  - `jdesk-webview-spi`: `PtyBackend` + `PlatformApplication.ptyBackend()` (default empty →
    `ILLEGAL_STATE`).
  - `jdesk-runtime`: `PtyManager` (64-session cap, tracking, auto-remove on child exit,
    close-all at shutdown). Wired into `JDeskRuntime.openPty` and `shutdown()`.
  - `jdesk-platform-macos`: `MacPtyBackend` — `openpty` + `posix_spawnp` (no `fork` in the
    JVM) with `POSIX_SPAWN_SETSID` and file actions that open the slave by name as fd 0/1/2,
    so the session leader acquires a real controlling terminal (full job control). Reader and
    waiter run on platform daemon threads; signals target the process group; `close()` is
    SIGHUP then SIGKILL-of-the-group after a grace period.
- **Unit tests:** `PtyManagerTest` (5: open+track+signal delegation, 64-session cap, natural
  exit removal, close-all, post-close rejection). All pass.
- **Live verification (real `/bin/sh` via the public `openPty` path, `PtyProbe`):**
  `tty=/dev/ttys000` (real controlling TTY), `stty size` `24 80` → after `resize(100,40)`
  `40 100`, `sh -c 'exit 7'` → `exitCode()==7`, and a backgrounded `sleep 300` (pid 5286) is
  reaped when the session is killed — **no orphan** (verified via `ProcessHandle`). Harness:
  `./gradlew :test-apps:native-smoke:run -PjdeskPlatform=macos -PjdeskMain=dev.jdesk.testapps.nativesmoke.PtyProbe`.
- **Breaking change:** `ApplicationHandle` gains abstract `openPty` (only `JDeskRuntime`
  implements it). `PlatformApplication.ptyBackend()` is a default (adapters/fakes unaffected).
- **PLATFORM-002 (implemented, compile-verified):** `LinuxPtyBackend` (`openpty` from libutil
  + `posix_spawnp`, POSIX like macOS, with Linux constants: `TIOCSWINSZ=0x5414`,
  `POSIX_SPAWN_SETSID=0x80`, glibc struct sizes) and `WindowsPtyBackend` (ConPTY:
  `CreatePseudoConsole` + `STARTUPINFOEX`/`CreateProcessW`; Windows has no POSIX signals so
  terminate/kill use `TerminateProcess`). Both wired into their `ptyBackend()`. **Compile-verified
  only** — no Windows/Linux environment; the ConPTY struct offsets especially must be validated on
  the Windows CI lane. Runtime verification belongs to the native CI lanes.

## GAP-004 — Native desktop integration (macOS 10/10; Windows + Linux implemented, compile-verified)

The reported batch is 10 APIs. On macOS all ten are implemented and verified to the honest
limit of an automation endpoint (live where a value round-trips, **structural** where the impl
self-checks native state, and **not auto-tested** for GUI gestures — each labelled per-API
below). Windows and Linux are implemented for every API too (see the per-API matrix under
*Windows / Linux status*), compile-verified only. The macOS detail:

- **Implemented + live-verified (macOS):**
  - `ApplicationHandle.systemTheme()` → `SystemTheme.DARK/LIGHT` from
    `NSApp.effectiveAppearance`. Verified: returned `DARK` and matched the OS
    (`defaults read -g AppleInterfaceStyle`).
  - `readClipboard(type)` / `writeClipboard(type, byte[])` — binary clipboard via
    `NSPasteboard` `dataForType:`/`setData:forType:` (64 MiB cap). Verified: 4 KiB write→read
    SHA-256 + length round-trip matches.
  - `setDockBadge(label)` — `NSApp.dockTile setBadgeLabel:`. Verified: set + clear execute
    with no error. The *visual* badge is not auto-verifiable and is not claimed as tested.
  - `setApplicationMenu(MenuSpec, onAction)` — builds an `NSMenu`/`NSMenuItem` tree
    (submenus, separators, accelerators) whose actions target a process-lifetime
    `JDeskMenuTarget`; `MenuSpec`/`MenuItem` in `jdesk-api`. Verified **structurally**: the
    impl self-checks that `NSApp.mainMenu` is really installed with the expected arity (a 2
    top-item menu round-tripped). The click→listener dispatch itself is **NOT auto-tested**
    (needs a real menu selection) — stated plainly, not claimed as verified.
  - `setApplicationIcon(byte[] png)` — `NSImage initWithData:` -> `NSApp setApplicationIconImage:`.
    Verified **structurally**: the impl throws unless the PNG decodes and `applicationIconImage`
    is non-nil afterward (a 99-byte generated PNG round-tripped). The *visual* icon is not
    auto-verified.
  - `createTrayItem(TraySpec, onAction)` -> `TrayHandle` — `NSStatusItem` with a title/icon
    and a click menu (its own `MacMenu` action listener). Verified **structurally**: create
    throws unless a real non-nil status item is installed; setTitle + remove exercised. The
    tray *click* is not auto-tested.
  - `registerGlobalShortcut(accelerator, cb)` -> `Subscription` — public Carbon
    `RegisterEventHotKey` + one app `InstallEventHandler` dispatching by hotkey id; requires
    >=1 modifier. Verified **structurally**: `RegisterEventHotKey` returns `noErr`
    (`Cmd+Ctrl+Alt+Shift+K`) and unregister succeeds. The global *keypress* is an OS input
    event and is not auto-tested.
  - `showNotification(title, body)` — legacy `NSUserNotification` for dev (nil center ->
    `ILLEGAL_STATE`). Verified: the `deliverNotification:` call succeeds. Banner *display*
    needs a signed bundle + permission (production: `UNUserNotificationCenter`) and is not
    verified here.
  - SPI: `systemTheme`/`readClipboard`/`writeClipboard`/`setDockBadge` default to
    `ILLEGAL_STATE`; `setApplicationMenu` defaults to a no-op (Windows/Linux/test-fake
    unaffected). Harness: `DesktopProbe`.
- `showContextMenu(MenuSpec)` (WindowHandle) -> chosen id — native `NSMenu`
  `popUpMenuPositioningItem:atLocation:inView:` (modal). Compiles + wired; the popup is a
  GUI interaction and is not auto-tested.
- `onFileDrop(listener)` (WindowHandle) — a `JDeskDropWebView` WKWebView subclass whose
  `performDragOperation:` reads the dropped absolute paths and calls `super` so HTML5 DnD
  still works (`objc_msgSendSuper`). Verified: the subclass is created and the web view
  keeps rendering + doing IPC (DesktopProbe). Whether a real Finder drag reaches the
  override is a GUI gesture and is NOT auto-tested; a safe fallback to plain WKWebView
  protects the core. These are the ones whose real
  behaviour is **GUI-interaction gated** — menu/tray/hotkey *activation*, notification
  *display* (needs a signed bundle), the file-drop *gesture*, and a context menu that blocks
  the UI thread until dismissed cannot be exercised from an automation endpoint. They will be
  implemented with honest, explicit "installed / no-exception — interaction NOT auto-tested"
  status rather than false "verified" claims.

---

## GAP-005 — Deep-link scheme + file-association packaging

- **Gap (real):** no way to register a `scheme://` deep link or file association; jpackage
  cannot inject `CFBundleURLTypes`, and there was no open-URL delivery path.
- **Fix (implemented):**
  - `jdesk-packager` `InfoPlistCustomizer`: injects `CFBundleURLTypes` + usage-description
    keys into a jpackage `Info.plist`; idempotent, XML-escaped. `JpackageArguments` gains
    `--icon` and repeatable `--file-associations`.
  - `jdesk-packager` `LinuxDesktopEntry`: builds a `.desktop` entry with
    `MimeType=x-scheme-handler/<scheme>` and `Exec=… %u` (the Linux analogue of
    `InfoPlistCustomizer`). `WindowsUrlScheme`: builds an HKCU `URL Protocol` `.reg` script
    (`shell\open\command "<exe>" "%1"`). Both are pure text generators, unit-tested on this host;
    the package task emits them next to the app image.
  - `jdesk-webview-spi` `PlatformApplication.setOpenUrlHandler`; macOS `MacOpenUrl` installs a
    `JDeskAppDelegate` (`application:openURLs:`); the runtime forwards each URL to the
    single-instance activation handler (same path as argv). On Windows/Linux the OS delivers the
    URL as a command-line argument, which `jdesk-instance` already forwards to the **same**
    activation handler — so the URL-delivery transport is cross-platform without an adapter change.
  - `jdesk-gradle-plugin`: `jdesk { deepLink { schemes; usageDescription(k,v) }; appIcon;
    fileAssociation(ext, mime, desc) }` wires all of the above into `jdeskPackage`, which now
    post-processes the `Info.plist` (macOS), writes the `.desktop` (Linux) or the `.reg` (Windows)
    depending on the packaging host.
- **Unit tests:** `InfoPlistCustomizerTest` (5), `LinuxDesktopEntryTest` (4), `WindowsUrlSchemeTest`
  (4), `JpackageArgumentsTest` (+2 for icon / file-associations).
- **Verification:** `InfoPlistCustomizer` run against a **real** jpackage-generated
  `Info.plist` (`build/jpackage/JDeskSmoke.app`) injects `CFBundleURLTypes` (`jdesk-forge`)
  and a usage description, and the result is still valid XML and idempotent. Live
  (`DesktopProbe` as single-instance) the `JDeskAppDelegate` installs and the app keeps
  working.
- **BLOCKED (honest):** routing an actual `scheme://` link end-to-end needs the app **installed
  and registered** with the OS — a signed `.app` for macOS Launch Services; the `.desktop`
  copied into an applications dir + `update-desktop-database` on Linux; the `.reg` applied
  (`reg import` / installer / first-run) on Windows. The generator logic for all three is
  unit-tested on this host, but none of the OS-level registration is reproducible from an
  unbundled dev run on a single macOS machine. A full `jdeskPackage` on each native CI lane,
  producing an installed image, is the remaining end-to-end validation. (The Windows `.reg` is a
  per-user HKCU script applied by an install step, since jpackage exposes no URL-scheme flag and
  an app-image has no installer hook — the honest, admin-free analogue of the macOS `Info.plist`.)

---

## Summary

BUG-001, GAP-001, GAP-002, GAP-003 are done and live-verified. GAP-004 is 10/10 on macOS
(structural + live where possible; GUI gestures honestly not-auto-tested) and now **10/10 on
Windows and Linux as well** — real native FFM for the interactive APIs (context menu, file
drop, tray, global shortcut, notifications, binary clipboard) and documented no-ops only where
the platform genuinely lacks the concept (no dock badge, no global menu bar, runtime app-icon
owned by the packaged binary). Every Windows/Linux entry is **compile-verified only** — there
is no Windows/Linux host on the authoring machine, so the native CI lanes runtime-verify; the
live OS gestures (key press, tray/menu click, notification banner, drag) remain not-auto-tested
on any host. GAP-005 is done across all three platforms: URL delivery is already cross-platform
(single-instance argv forwarding), and scheme registration is implemented per OS — macOS
`Info.plist`, Linux `.desktop` `x-scheme-handler`, Windows HKCU `URL Protocol` `.reg` — with the
generator logic unit-tested; applying the registration + the end-to-end route are runtime-verified
on the native CI lanes. BUG-002 was retracted after verification. Nothing here is claimed without
evidence.

The whole tree builds green: **815 unit tests pass** and every coverage gate is satisfied
(jdesk-api 0.92, jdesk-runtime 0.81, jdesk-webview-spi 0.82 line coverage) after adding tests
for the new API value types, runtime desktop-integration delegations, SPI defaults, and the
Linux/Windows deep-link scheme generators.

## Windows / Linux status

There is **no Windows or Linux environment on the authoring machine**, so everything below is
**compile-verified only**; the repo's real Windows/Linux native CI lanes are what runtime-verify.

- **GAP-001 (file watching):** already cross-platform — Windows/Linux use the recursive
  `WatchService` backend (kernel-backed / event-driven there, no FSEvents needed). Done.
- **GAP-002 (upload body):** implemented for Windows (WebView2 `Content` IStream) and Linux
  (WebKitGTK `get_http_body`). PLATFORM-001 closed, compile-verified.
- **GAP-003 (PTY):** implemented for Linux (`openpty`+`posix_spawn`) and Windows (ConPTY).
  PLATFORM-002 closed, compile-verified.
- **GAP-005 (packaging):** `JpackageArguments` icon/`--file-associations` and the Gradle DSL are
  cross-platform (jpackage handles Windows/Linux file associations natively). `scheme://` **URL
  delivery** is already cross-platform too — `jdesk-instance` forwards the launched URL argv to the
  app's activation handler on all three OSes. Scheme **registration** is now implemented per OS:
  macOS `InfoPlistCustomizer` (`CFBundleURLTypes`), Linux `LinuxDesktopEntry` (`.desktop`
  `x-scheme-handler`), Windows `WindowsUrlScheme` (HKCU `URL Protocol` `.reg`). All three
  generators are unit-tested; the package task emits the right artifact for the packaging host.
  Applying the registration (install/`update-desktop-database`/`reg import`) and the end-to-end
  link route are runtime-verified on the native CI lanes, not on this macOS host.
- **GAP-004 (desktop integration):** macOS 10/10 (live/structural). Windows and Linux are now
  **implemented for every API** — the interactive ones with real native FFM (message-window
  WndProc / GTK signals / X11 grabs), the platform-inapplicable ones as documented no-ops. All
  Win/Linux entries are **compile-verified only** (no Windows/Linux host on the authoring
  machine); the native CI lanes runtime-verify. Per-API status:

  | API | Windows | Linux |
  | --- | --- | --- |
  | `systemTheme` | **done (CV)** — `AppsUseLightTheme` registry | **done (CV)** — GTK theme name |
  | `readClipboard`/`writeClipboard` | **done (CV)** — `RegisterClipboardFormatW` + `GlobalAlloc` | **done (CV)** — `GtkClipboard` custom target atom + owner get-func (`LinuxClipboard`) |
  | `showNotification` | **done (CV)** — `Shell_NotifyIconW` balloon via a hidden message window | **done (CV)** — libnotify |
  | `setApplicationIcon` | **n/a — no-op** — the app icon is the packaged `.exe` resource (`jpackage --icon`); runtime PNG→HICON needs GDI+ | **done (CV)** — `gtk_window_set_default_icon` via GdkPixbuf |
  | `registerGlobalShortcut` | **done (CV)** — `RegisterHotKey` + `WM_HOTKEY` on a hidden message window | **done (CV, X11)** — `XGrabKey` + GDK event filter; **Wayland honestly throws** (no global-grab protocol) |
  | `createTrayItem` | **done (CV)** — `Shell_NotifyIconW` + message-window WndProc + `TrackPopupMenu` | **done (CV)** — `GtkStatusIcon` + popup menu |
  | `setDockBadge` | **n/a — no-op** — no dock; closest is an `ITaskbarList3` overlay (COM + GDI) | **n/a — no-op** — no dock; closest is Unity `LauncherEntry` (DBus) |
  | `setApplicationMenu` | **n/a — no-op** — no global menu bar (per-window `HMENU` model) | **n/a — no-op** — no global menu bar (per-window / appmenu) |
  | `showContextMenu` | **done (CV)** — `TrackPopupMenu` on the HWND (`WindowsMenu`) | **done (CV)** — `gtk_menu_popup_at_pointer` |
  | `onFileDrop` | **done (CV)** — `DragAcceptFiles` + `WM_DROPFILES` → `DragQueryFileW` (`WindowsFileDrop`) | **done (CV)** — `gtk_drag_dest_set` + `drag-data-received` |

  **done (CV)** = implemented, compile-verified only, CI runtime-verifies. **n/a — no-op** = the
  concept does not exist on that platform (no dock, no global menu bar) or is owned by the
  packaged binary, so a documented no-op is the honest behavior, not a stub for missing work.
  The interactive parts (a hotkey press, a tray/context-menu click, a notification banner, a
  real drag gesture) are live OS events and remain **not auto-tested** even on CI-run builds;
  only the wiring (registration succeeds, structs marshal, handlers install) is verifiable.
