# Build a networked / real-time app

JDesk runs real-time apps well — a game or dashboard can push a canvas at 60 fps, hold a
low-latency WebSocket, and use Web Audio, all over the `jdesk://app/` origin. What it does
**not** ship is a networking or audio stack: you choose those, the same way Tauri leaves
them to you. This guide covers the patterns that work.

## Talking to a server (WebSocket / HTTP)

### 1. Widen `connect-src` in the CSP

The strict default CSP has `connect-src 'self'`, which blocks any socket or fetch outside
the app origin. Widen just that directive with the per-directive [`Csp`](../reference/java-api.md#csp) builder — the rest of the strict policy stays intact:

```java
JDeskApplication.builder()
    .contentSecurityPolicy(Csp.defaults()
        .connectSrc("'self'", "ws://127.0.0.1:7777", "wss://api.example.com"))
    // object-src 'none', base-uri 'none', script-src 'self' … unchanged
    .run(args);
```

For a LAN game, `ws://` to a loopback/LAN address; over the internet use `wss://`.

### 2. Pick a client/server library

The framework has no built-in server. On the JVM side, any library works — for a small
authoritative game server, [`org.java-websocket`](https://github.com/TooTallNate/Java-WebSocket)
plus Jackson is a proven, dependency-light choice; for HTTP, the JDK's `HttpClient`/`HttpServer`
or your framework of choice. Run the server in-process (a background thread started from a
`LifecycleListener.onReady`) or as a separate process the app connects to.

### 3. Keep the WebView as the socket owner

Open the WebSocket **from the page** (`new WebSocket("ws://127.0.0.1:7777")`), not through
IPC. The page ↔ server link then bypasses the framework entirely, so the 20 Hz snapshot
stream never touches the IPC limits below. Use a typed command only to hand the page its
connection config (e.g. `app.serverUrl()`), then let the WebView connect directly.

## When NOT to use IPC

IPC (`@DesktopCommand` ↔ `invoke`) is a **request/response control plane**, not a data
plane. It is deliberately bounded (spec section 10): each message is capped at **1 MiB**,
the per-window event queue holds **256** events, and everything is JSON both ways. That is
right for "save settings", "open a file", "start a job" — and wrong for a 20 Hz game state
feed or a binary media stream. Rule of thumb:

| Traffic | Use |
| --- | --- |
| Commands, config, occasional events | IPC (`@DesktopCommand`, `context.events().emit`) |
| High-rate game/telemetry state | A WebSocket opened from the page |
| Large files / blobs from Java | [Binary streaming](streaming-binary-data.md) (`BinaryStream` → `invokeStream`) |
| Java-served images/binaries | [Asset routes](serving-assets.md) (`jdesk://app/proxy/...`) |

## Audio

Web Audio in the WebView is enough for most apps and needs no native code. Bundle the
sound with your assets and decode it at load:

```js
const buffer = await fetch("jdesk://app/sfx/hit.wav")
    .then(r => r.arrayBuffer())
    .then(bytes => audioContext.decodeAudioData(bytes));
// play: const src = audioContext.createBufferSource(); src.buffer = buffer; ...
```

On macOS/WKWebView audio starts without a user gesture; on other engines you may need to
resume the `AudioContext` after the first input event. Reach for native audio only when
you need sub-frame latency the WebView can't give — not covered here.

## Running two instances (multiplayer testing)

Two clients against one server is the common local test. `singleInstance` is **off by
default**, so two launches coexist — but two `./gradlew run` in the same checkout contend
for the Gradle project lock. Use the installed distribution instead:

```bash
./gradlew installDist
# terminal A
./build/install/<app>/bin/<app>
# terminal B
./build/install/<app>/bin/<app>
```

The macOS launcher script already carries `-XstartOnFirstThread`, `--enable-native-access`,
and the packaged `jdesk.assets.dir`. To place the two windows side by side, set an explicit
[position](../reference/java-api.md#windowconfigbuilder) per instance
(`WindowConfig.builder().position(x, y)`), or select the instance by a launch arg your
`Main` reads. If you enabled `singleInstance`, give each instance a distinct
`-Djdesk.instance.dir=<dir>` so their locks don't collide.

## Debugging the running app

Two flags make the WebView observable from outside — reach for them the moment the UI gets
non-trivial:

- `-Djdesk.console.forward=true` — the page's `console.*` and uncaught errors (including
  the earliest module/parse failures) print to the app's Java log.
- `-Djdesk.automation=true` — a token-gated loopback HTTP endpoint (`/windows`,
  `/evaluate`, `/input`, `/snapshot`, `/console`) to drive gameplay, read state, and
  capture the WebView as a PNG. See [Automate and E2E-test your app](automation-and-e2e.md).

`./gradlew run` forwards any `-Djdesk.*` flag to the app, so
`./gradlew run -Djdesk.console.forward=true` just works.
