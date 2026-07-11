# JDesk IPC protocol (version 1)

The IPC protocol version is an independent integer (`v`), currently `1`. The WebView
never receives Java objects; every message is a versioned JSON envelope over the
platform bridge. Unknown top-level fields, wrong types, and unknown kinds are rejected
deterministically before any user code runs.

## Nonce lifecycle

Each main-frame navigation creates a fresh navigation session with a 128-bit random
nonce. The platform layer injects it into the page; the client echoes it in every
envelope. A stale or wrong nonce can never reach command execution:

- `hello` with a stale nonce is ignored.
- `invoke` with a stale nonce fails with `STALE_NONCE`.
- `cancel` with a stale nonce is ignored.
- Navigation invalidates the previous session, rejects new invokes bound to it, cancels
  outstanding requests after a grace period, and drops late results and queued events so
  nothing reaches the new document.

## Client → runtime

### hello

```json
{"v":1, "kind":"hello", "client":"@jdesk/client", "clientVersion":"0.1.0", "nonce":"<hex>"}
```

Required before any `invoke`. The runtime replies with `helloAck`. Unsupported `v`
fails with `PROTOCOL_VERSION_UNSUPPORTED`.

### invoke

```json
{"v":1, "kind":"invoke", "id":"01J...", "command":"greeting.greet",
 "payload": {"name":"Tuan"}, "nonce":"<hex>"}
```

- `id`: 1..128 chars, unique within the navigation session (duplicates fail with
  `INVALID_REQUEST`).
- `command`: 1..128 chars, dot-separated lowerCamel segments.
- `payload`: optional; stays raw until the capability check passes.

### cancel

```json
{"v":1, "kind":"cancel", "id":"01J...", "nonce":"<hex>"}
```

Best-effort: the runtime interrupts the worker virtual thread and sends exactly one
terminal result with code `CANCELLED`.

## Runtime → client

### helloAck

```json
{"v":1, "kind":"helloAck", "ok":true, "nonce":"<hex>"}
```

### result (success / failure)

```json
{"v":1, "kind":"result", "id":"01J...", "ok":true, "value":{"message":"Hello Tuan"}}
{"v":1, "kind":"result", "id":"01J...", "ok":false,
 "error":{"code":"CAPABILITY_DENIED", "message":"Command is not allowed for this window"}}
```

Exactly one terminal result per request. Results correlate by `id` and may complete out
of order. Error `code` is one of the public `ErrorCode` names; `message` never contains
class names, stack traces, paths, SQL, secrets, or internal exception text.

### event

```json
{"v":1, "kind":"event", "event":"download.progress", "payload":{"pct":42}}
```

Events from one emitter to one window preserve enqueue order. Queues are bounded with a
configured overflow policy: `REJECT` (default), `DROP_OLDEST`, or `COALESCE` (by event
name).

## Processing order for invoke

1. Envelope size check (max 1 MiB encoded) and strict parse.
2. Nonce check (`STALE_NONCE`).
3. Handshake check (`INVALID_REQUEST` before `hello`).
4. Request-id uniqueness (`INVALID_REQUEST`).
5. Registry lookup (`UNKNOWN_COMMAND`).
6. Capability evaluation — before payload deserialization (`CAPABILITY_DENIED`).
7. In-flight limit, default 128/window (`LIMIT_EXCEEDED`).
8. Payload deserialization (`SERIALIZATION_ERROR`, `PAYLOAD_TOO_LARGE`,
   `INVALID_REQUEST` when a required payload is missing).
9. Handler runs on a virtual thread with a timeout (default 30 s, per-command override;
   `TIMEOUT`).

Over-limit or malformed requests fail deterministically and never execute user code.

## Limits (configurable downward only)

| Limit | Default |
| --- | --- |
| Max encoded incoming message | 1 MiB |
| Max in-flight invocations per window | 128 |
| Max command duration | 30 s (per-command metadata may override) |
| Max queued events per window | 256 |
| Max command/event name length | 128 chars |
| Max JSON nesting depth | 64 |

## Compatibility policy

- The protocol version increments only for breaking wire changes.
- The generated JS client declares its supported protocol range; the runtime rejects
  unsupported versions during `hello`.
- After the first stable release, fixtures for the previous protocol version become
  compatibility tests (spec section 25).
