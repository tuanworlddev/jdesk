# Error codes reference

`dev.jdesk.api.ErrorCode` enumerates the safe, public error identifiers. These are the
only error codes that may cross the IPC boundary to the frontend. A failed command result
carries `{code, message}` where `code` is one of these names and `message` is a
frontend-safe string that never contains class names, stack traces, file paths, SQL,
secrets, or internal exception text.

In production, an uncaught handler failure maps to `INTERNAL_ERROR` with the generic
message `Command failed`; a thrown [`JDeskException`](java-api.md#jdeskexception) forwards
its `code()` and `publicMessage()` verbatim. See the
[IPC protocol](../architecture/ipc-protocol.md) for the exact processing order.

## Codes

The **Crosses to frontend** column reflects what reaches the WebView as a `result` or
`helloAck` on the wire, per the IPC protocol.

| Code | When it occurs | Crosses to frontend |
| --- | --- | --- |
| `INVALID_REQUEST` | Malformed or invalid request: duplicate request id, missing required payload, invalid application/window id or config, malformed origin. | Yes — as an `invoke` result error. |
| `PROTOCOL_VERSION_UNSUPPORTED` | `hello` declares an IPC protocol version the runtime does not support. | Yes — as a `helloAck` with `ok:false`. |
| `STALE_NONCE` | `invoke` carries a nonce from a superseded navigation session. (`hello` and `cancel` with a stale nonce are silently ignored.) | Yes — as an `invoke` result error. |
| `UNKNOWN_COMMAND` | No command with the requested wire name is registered. | Yes — as an `invoke` result error. |
| `CAPABILITY_DENIED` | Capability evaluation denies the command for this window (checked before payload deserialization). The message never reveals which capabilities exist. | Yes — as an `invoke` result error. |
| `PAYLOAD_TOO_LARGE` | Encoded message exceeds the size limit (1 MiB), or a JSON document exceeds the JSON bounds. | Yes — as an `invoke` result error. |
| `LIMIT_EXCEEDED` | In-flight invocation limit per window (default 128) exceeded; or an event queue is full under the `REJECT` overflow policy. | Yes — as an `invoke` result error (in-flight). Event-queue overflow surfaces server-side as a `JDeskException` from `EventEmitter.emit`. |
| `TIMEOUT` | A handler exceeds its timeout (default 30 s; validated per-command override up to 24 h). | Yes — as an `invoke` result error. |
| `CANCELLED` | The client sent `cancel`, or the request was otherwise cancelled/interrupted. Exactly one terminal result is sent. | Yes — as an `invoke` result error. |
| `SERIALIZATION_ERROR` | Payload deserialization or result encoding fails; invalid stream request. | Yes — as an `invoke` result error. |
| `NAVIGATION_BLOCKED` | A navigation to a disallowed origin is blocked by navigation policy. | No — enforced in the native/navigation layer, not delivered as an IPC result. Defined in the API; not emitted on the IPC result channel by the current runtime. |
| `ASSET_NOT_FOUND` | A request to the app origin (`jdesk://app/`) has no matching asset. | No — surfaced as an asset (HTTP-like) response, not an IPC result. Defined in the API; not emitted on the IPC result channel by the current runtime. |
| `WINDOW_CLOSED` | A command or operation targets an unknown or already-closed window. | Yes — as an `invoke` result error (`Unknown or closed window`); also completes window/handle stages exceptionally. |
| `ALREADY_CLOSED` | An operation is attempted on an already-released native handle (FFM layer); release runs exactly once. | No — internal/native handle lifecycle; not sent as an IPC result. |
| `ILLEGAL_STATE` | Construction/configuration errors: duplicate command name, wrong number of `JDeskBootstrap` providers, capability-resource problems, off-UI-thread assertions in dev/test. | No — thrown at startup/configuration or off the wire, not delivered as an IPC result. |
| `INTERNAL_ERROR` | Fallback for an unexpected handler or runtime failure. | Yes — as an `invoke` result error with a generic message; internal detail is stripped. |

## Notes

- The `message` field on the wire is always safe. For a `JDeskException`, it is
  `publicMessage()`; for an uncaught failure in production, it is a fixed generic string.
- Which codes appear on the wire is governed by the
  [IPC processing order](../architecture/ipc-protocol.md#processing-order-for-invoke):
  size check → nonce → handshake → request-id uniqueness → registry lookup → capability →
  in-flight limit → deserialization → handler timeout. Over-limit or malformed requests
  fail deterministically and never execute user code.
- `NAVIGATION_BLOCKED`, `ASSET_NOT_FOUND`, `ALREADY_CLOSED`, and `ILLEGAL_STATE` are part
  of the public enum but, in the current runtime, are raised outside the IPC result
  channel (navigation policy, asset serving, native handle lifecycle, and
  startup/configuration respectively). See [Verification status](../../VERIFICATION.md)
  for platform coverage.

## See also

- [Java API — `ErrorCode` and `JDeskException`](java-api.md#errors)
- [IPC protocol](../architecture/ipc-protocol.md) — wire envelopes and error `code`/`message`.
- [Threat model](../security/threat-model.md) — why production errors never leak internals.
