# Emit events to the frontend

Commands let the frontend call Java; events let Java push data to the frontend. This guide
shows you how to emit an event from a command handler and subscribe to it in JavaScript.
It assumes you can already [define a command](defining-commands.md).

## Emit an event from Java

Every command handler receives an `InvocationContext`. Its `events()` method returns an
`EventEmitter` targeting the window that made the call. Call `emit(name, payload)` to send
an event to that window:

```java
@DesktopCommand("download.start")
@RequiresCapability("download:use")
public CompletionStage<Void> start(DownloadRequest request, InvocationContext context) {
    return CompletableFuture.runAsync(() -> {
        for (int pct = 0; pct <= 100; pct += 10) {
            transferChunk(request);
            context.events().emit("download.progress", new Progress(pct));
        }
        context.events().emit("download.done", null);
    }).thenApply(ignored -> null);
}

public record Progress(int pct) {
}
```

- `eventName` is 1..128 characters with the same grammar as command names
  (`area.name`).
- `payload` is serialized with the runtime's JSON codec and may be `null`.
- The payload is any type the codec can serialize; use a record for structured data, the
  same way you shape [command responses](defining-commands.md#request-and-response-types).

The emitter targets the **invoking window**. To emit outside a command handler, or to a
window other than the caller, hold the emitter you need and call it from your own code; the
`Subscription` handle returned by runtime registrations is `AutoCloseable`, so close it to
release the registration when you are done.

## Subscribe on the JavaScript side

Use `on(name, handler)` from `@jdesk/client`. It returns an unsubscribe function:

```ts
import { on } from "@jdesk/client";

const off = on("download.progress", (payload) => {
  const { pct } = payload as { pct: number };
  progressBar.value = pct;
});

on("download.done", () => {
  off(); // stop listening for progress
  showComplete();
});
```

Call the returned function to remove the handler; when it is the last handler for that
event, its slot is released.

### Without the client library

Events arrive as `jdesk-message` document events whose `detail` is the raw JSON envelope
with `kind: "event"`. Subscribe directly if you are not using `@jdesk/client`:

```js
document.addEventListener("jdesk-message", function (event) {
  var message = JSON.parse(event.detail);
  if (message.kind === "event" && message.event === "download.progress") {
    updateProgress(message.payload.pct);
  }
});
```

The event envelope looks like this (see [IPC protocol](../architecture/ipc-protocol.md) for
the full format):

```json
{ "v": 1, "kind": "event", "event": "download.progress", "payload": { "pct": 42 } }
```

## Ordering and the bounded queue

Events from one emitter to one window are delivered in **enqueue order**. Each window has a
bounded event queue (256 events by default; see the
[IPC protocol limits](../architecture/ipc-protocol.md#limits-configurable-downward-only)).
When the queue is full, the configured overflow policy decides what happens:

| Policy | Behavior when the queue is full |
| --- | --- |
| `REJECT` (default) | `emit` throws `JDeskException` with `LIMIT_EXCEEDED`; nothing is dropped |
| `DROP_OLDEST` | the oldest queued event is dropped and counted |
| `COALESCE` | a queued event with the same name is replaced by the new one |

Design high-frequency events (progress, cursor position, log tails) around this. With
`REJECT`, handle the `LIMIT_EXCEEDED` exception rather than letting it fail the command.
For a stream of same-named updates where only the latest matters, `COALESCE` keeps the
queue bounded without losing the final value. Navigation drops queued events bound to the
previous document, so events never leak across a page load.

## Related

- [Define a command](defining-commands.md) — where `InvocationContext` comes from.
- [IPC protocol](../architecture/ipc-protocol.md) — the event envelope, ordering, and limits.
- [Threading](../concepts/threading-and-the-event-loop.md) — emitting from virtual threads and background work.
