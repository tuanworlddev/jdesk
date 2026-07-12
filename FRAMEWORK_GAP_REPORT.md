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
| GAP-002 | Binary upload JS→Java (POST body) | **Done (macOS)** | Unit tests + live WKWebView SHA-256 match; Win/Linux body forwarding open (PLATFORM-001) |
| GAP-001 | File watching API | **Done (macOS FSEvents + portable)** | Unit tests + live FSEvents latency ~10–13 ms |
| BUG-002 | `requestStop()` "doesn't wake idle loop" | **Retracted — not a real bug** | See below |
| GAP-003 | PTY / process API | **Done (macOS)** | Unit tests + live shell (tty, resize, exit code, no-orphan) |
| GAP-004 | Native desktop integration batch | **Partial (8 of 10, macOS)** | Live: theme, clipboard, badge, menu, icon, tray, shortcut, notification |
| GAP-005 | Deep-link scheme + file association | Not started | — |

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
- **PLATFORM-001 (open):** Windows (WebView2) and Linux (WebKitGTK) adapters do not forward
  the request body yet — routes see an empty `body()` there. Not implemented because there is
  no Windows/Linux environment to live-verify against, and unverified native code will not be
  claimed as working.

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
- **PLATFORM-002 (open):** Windows (ConPTY) and Linux (openpty/`libutil`) not implemented —
  no environment to live-verify; unverified native code will not be claimed as working.

## GAP-004 — Native desktop integration (PARTIAL: 3 of 10)

The reported batch is 10 APIs. Delivered so far are the three that can be **fully
round-trip verified** on this machine, so no unverifiable claims are made:

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
- **Not yet implemented (2 of 10):** context menu, file-drop paths. These are the ones whose real
  behaviour is **GUI-interaction gated** — menu/tray/hotkey *activation*, notification
  *display* (needs a signed bundle), the file-drop *gesture*, and a context menu that blocks
  the UI thread until dismissed cannot be exercised from an automation endpoint. They will be
  implemented with honest, explicit "installed / no-exception — interaction NOT auto-tested"
  status rather than false "verified" claims.

---

## Not started

GAP-005 (deep-link + file association packaging) is **not implemented**, and GAP-004 is
partial (see above). Tracked in the task list; this file is updated only as work really
lands.
