# Configure and manage windows

This guide shows you how to configure a native window, open more than one, and respond to
lifecycle events such as close requests. It assumes you have a working app from
[Your first app](../getting-started/your-first-app.md).

## Configure a window

Build a `WindowConfig` and add it to the application. Each window has a stable id, a title,
a size, a resizable flag, and an entry URL:

```java
import dev.jdesk.api.WindowConfig;

WindowConfig main = WindowConfig.builder()
        .id("main")
        .title("Example")
        .size(1100, 720)
        .resizable(true)
        .entry("jdesk://app/index.html")
        .build();
```

| Setting | Default | Notes |
| --- | --- | --- |
| `id` | required | 1..64 chars of `[a-zA-Z0-9._-]`; matches window ids in [capabilities.json](capabilities-and-permissions.md) |
| `title` | `""` | window title bar text |
| `size(width, height)` | `800 × 600` | each dimension 1..32767 |
| `resizable` | `true` | whether the user can resize the window |
| `entry` | required | the initial URL, over [the app origin](serving-assets.md) `jdesk://app/...` |

The entry points at your production assets served over `jdesk://app/`. See
[Serve production assets](serving-assets.md) for how that origin resolves files.

Add the window to the builder:

```java
JDeskApplication.builder()
    .id("com.example.app")
    .commands(GreetingServiceCommands.create(greetings))
    .capabilities(Capabilities.fromResource(
        Main.class.getModule(), "jdesk-capabilities.json"))
    .window(main)
    .run(args);
```

## Open multiple windows

Call `.window(...)` once per window you want open at startup. Give each a distinct id:

```java
JDeskApplication.builder()
    .id("com.example.app")
    .commands(/* ... */)
    .capabilities(/* ... */)
    .window(WindowConfig.builder()
        .id("main").title("Editor").size(1100, 720)
        .entry("jdesk://app/index.html").build())
    .window(WindowConfig.builder()
        .id("inspector").title("Inspector").size(400, 720)
        .entry("jdesk://app/inspector.html").build())
    .run(args);
```

Grant capabilities per window id, so `main` and `inspector` can hold different
capabilities. See [Grant capabilities per window](capabilities-and-permissions.md).

To open a window after startup, use the `ApplicationHandle` (below) and call
`openWindow(config)`. It reserves the window id before UI-thread creation begins and
completes with a `WindowHandle`:

```java
application.openWindow(WindowConfig.builder()
        .id("prefs").title("Preferences").size(520, 480)
        .entry("jdesk://app/prefs.html").build());
```

## Respond to lifecycle events

Register a `LifecycleListener` to hook the application lifecycle. Every method has a no-op
default, so override only what you need:

```java
import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowId;

JDeskApplication.builder()
    .id("com.example.app")
    // ...
    .lifecycle(new LifecycleListener() {
        @Override
        public void onReady(ApplicationHandle application) {
            // The app is up; keep the handle to open windows or request shutdown later.
            App.application = application;
        }

        @Override
        public boolean onCloseRequested(WindowId windowId) {
            // Return false to veto the close (e.g. unsaved changes).
            return !hasUnsavedWork(windowId);
        }

        @Override
        public void onStopping() {
            flushState();
        }
    })
    .run(args);
```

| Hook | When it fires |
| --- | --- |
| `onStarting()` | the application is starting up |
| `onReady()` / `onReady(ApplicationHandle)` | the application is ready; the overload hands you the control handle |
| `onCloseRequested(WindowId)` | a window was asked to close; return `false` to veto |
| `onStopping()` | orderly shutdown has begun |
| `onStopped()` | the application has stopped |

These correspond to the forward-only `LifecycleState` values `NEW → STARTING → READY →
STOPPING → STOPPED`. Transitions never go backward.

The `ApplicationHandle` from `onReady(ApplicationHandle)` is the control plane for the
running app. Use it to open windows (`openWindow`), look one up (`window(WindowId)`), read
`platform()` info, reach the `UiDispatcher` via `ui()`, or `requestStop()` for orderly
shutdown.

## Do not block the UI thread

Native window and WebView objects are created, called, and destroyed only on their UI
thread. When you need to touch the UI thread, marshal onto it with the `UiDispatcher` from
`application.ui()`:

```java
application.ui().execute(() -> {
    // runs on the UI thread
});

application.ui().submit(() -> computeOnUiThread()); // returns a CompletionStage
```

Never block the UI thread — no long computation, no blocking I/O, no waiting on a future
inside `execute`. Command handlers already run on virtual threads off the UI thread, so do
your work there and only hop to the UI thread for the final native call. In development and
test mode, calling a UI-thread-only operation off the UI thread throws `ILLEGAL_STATE`; in
production it logs and fails safe. See [Threading](../concepts/threading-and-the-event-loop.md) for the full
threading model.

## Related

- [Serve production assets](serving-assets.md) — what `entry` and the app origin resolve to.
- [Grant capabilities per window](capabilities-and-permissions.md) — per-window authorization.
- [Threading](../concepts/threading-and-the-event-loop.md) — the UI thread and virtual threads.
