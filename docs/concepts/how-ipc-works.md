# How IPC works

This page explains the model behind JDesk's inter-process communication: how the web
frontend and the Java core talk, why every message is a versioned JSON envelope, and why
the whole channel is asynchronous and authorized on the Java side. It is for readers who
want to understand the design and its trade-offs. For the exact wire format and error
codes, see the [IPC protocol reference](../architecture/ipc-protocol.md); for how to write
the code, see [Define a command](../guides/defining-commands.md) and
[Emit an event](../guides/emitting-events.md).

## The shape of the channel

The frontend never receives Java objects, and the Java core never receives frontend
objects. Everything that crosses the boundary is a string: a single JSON **envelope** with
a version field. The frontend calls Java by sending an `invoke` envelope; Java answers with
exactly one `result` envelope; Java pushes data to the frontend with `event` envelopes. A
short handshake (`hello` / `helloAck`) and a per-navigation `nonce` control envelope frame
the session.

This string-only rule is deliberate ([ADR-006](../architecture/ADR-006-async-message-passing.md)).
The bridge is a message channel between two very different worlds — a JavaScript engine in
the system WebView and a JVM — and the only thing both sides agree on is text. Passing
serialized envelopes rather than live object references means there is no shared heap, no
proxy lifetime to manage across the boundary, and a single, auditable choke point where
every message is validated. It also means the protocol can be versioned as a wire format
(`v`, currently `1`) independently of any language binding.

### The bridge

The channel itself is `window.__jdesk`, which JDesk calls **the bridge**. Each platform
adapter injects it as a document-start script over that platform's native message channel —
`webkit.messageHandlers.jdesk` on WKWebView and WebKitGTK, `chrome.webview` on WebView2.
The bridge exposes `window.__jdesk.post(string)` for outbound messages and dispatches
inbound messages as a `jdesk-message` `CustomEvent` on `document`. The generated
`jdesk-client` runtime wraps this so application code calls `invoke(...)` and `on(...)`
instead of touching the raw primitive.

The bridge primitive is visible to page script, and that is intentional — a channel the
page cannot reach would be useless. Security never rests on hiding `post`; it rests on the
checks the Java side runs on every envelope (see the
[security model](./security-model.md) and
[threat model](../security/threat-model.md)). Where a platform supports isolating the
bridge into a separate script world, JDesk uses it; where it does not, the capability gate
and CSP carry the weight.

## Why it is asynchronous

Every `invoke` returns a `Promise` in the frontend and is answered later by a correlated
`result`. Nothing about the call is synchronous, and that is a hard design choice, not an
implementation detail ([ADR-006](../architecture/ADR-006-async-message-passing.md)).

The native UI thread — the WebView's own thread — must stay responsive to keep the window
painting and handling input. A synchronous bridge would force one side to block the other:
either the page would freeze waiting for Java, or the UI thread would block waiting for a
command to finish. JDesk refuses both. The UI thread only copies a message and hands it
off; command handlers run on **virtual threads** and post their results back
asynchronously. A handler can take milliseconds or, with an explicit timeout override, much
longer, without ever stalling the renderer. The [threading model](./threading-and-the-event-loop.md)
page covers the thread rules in depth.

Asynchrony also gives the protocol its concurrency model for free. Many commands can be in
flight at once; each is correlated by a request `id`, and results may arrive in any order.
The frontend client keeps a map of pending calls keyed by `id` and resolves each promise
when its matching `result` arrives, so out-of-order completion is normal and correct rather
than a special case.

## Why it is compile-time typed

The set of callable commands is fixed when your code compiles, not discovered at runtime.
The `jdesk-codegen` annotation processor reads your `@DesktopCommand` methods and generates
a Java command registry plus a typed TypeScript client
([ADR-005](../architecture/ADR-005-compile-time-registration.md)). There is no classpath
scanning, no runtime reflection, and no hand-written glue that can drift out of sync between
the two languages.

The pay-off is that the request and response DTOs are the same contract on both sides. If a
command's payload record changes, the generated TypeScript changes with it, and a mismatched
call fails to compile rather than failing mysteriously at runtime. The runtime cost is a
`registry.find(command)` lookup on each invoke — a map lookup, not reflection — and payload
deserialization into a known record type with a defensive JSON codec. Polymorphic
deserialization and Jackson default typing stay disabled by design, because "deserialize
into whatever the payload claims to be" is exactly the gadget-chain surface JDesk wants to
keep closed.

## The per-navigation nonce and why it exists

Each main-frame navigation mints a fresh 128-bit random nonce (from `SecureRandom`), and
the runtime delivers it to the freshly committed document through a `nonce` control
envelope. The client echoes that nonce in every `hello`, `invoke`, and `cancel`. The
runtime's `NavigationSession` accepts an envelope only when its nonce matches the live
session and the session has not been invalidated.

The nonce answers a specific question: *is this message from the document that is actually
loaded right now?* Without it, an asynchronous result — or a deliberately crafted late
message — from a previous document could act after the page has navigated away, and a
sandboxed or embedded frame that forged a message could try to act as the main document.
The nonce makes cross-document replay unforgeable within the process: a message carrying a
stale or wrong nonce can never reach command execution. An `invoke` with a stale nonce fails
with `STALE_NONCE`; a stale `hello` or `cancel` is ignored. Because the nonce is delivered
to the top frame only, a sandboxed opaque-origin subframe cannot read it across the
origin boundary.

This is a lightweight, in-process integrity mechanism, not message authentication. Bridge
messages are not signed or MACed — within a single process, with no network transport to
intercept, the per-navigation nonce plus the origin and capability checks are what provide
unforgeability. That trade-off is stated honestly in the
[threat model](../security/threat-model.md).

## The invoke lifecycle, step by step

When an `invoke` string arrives, the per-window dispatcher runs a fixed sequence of checks.
The order is not arbitrary — cheaper and more security-critical checks come first, so a bad
request is rejected before it can cost anything, and *user code and payload deserialization
come last*:

1. **Size check and strict parse.** The encoded envelope must be at most 1 MiB, and it must
   parse as a JSON object whose fields are a closed set for its kind. Unknown fields, wrong
   types, and unknown kinds are rejected deterministically. An oversized message fails with
   `PAYLOAD_TOO_LARGE`; a malformed one with `INVALID_REQUEST`.
2. **Nonce check.** A stale or wrong nonce fails with `STALE_NONCE`.
3. **Handshake check.** An `invoke` before a successful `hello` fails with `INVALID_REQUEST`.
4. **Request-id uniqueness.** Each `id` must be unique within the navigation session;
   duplicates fail with `INVALID_REQUEST`. The session remembers a bounded set of used ids
   and fails closed if that bound is somehow reached.
5. **Registry lookup.** An unknown command name fails with `UNKNOWN_COMMAND`.
6. **Capability evaluation — before deserialization.** The dispatcher authorizes the call
   against the window's granted capabilities and committed origin. A denied call fails with
   `CAPABILITY_DENIED` *before the payload is deserialized and before your handler runs*.
   This ordering is central to the [security model](./security-model.md): a command your
   window is not allowed to call never touches your DTOs or your code.
7. **In-flight limit.** At most 128 invocations per window may be in flight; over the limit
   fails with `LIMIT_EXCEEDED`.
8. **Payload deserialization.** Only now is the raw payload decoded into the command's
   request record, with the defensive codec. Failures map to `SERIALIZATION_ERROR`,
   `PAYLOAD_TOO_LARGE`, or `INVALID_REQUEST` when a required payload is missing.
9. **Handler on a virtual thread.** The handler runs on a virtual thread with a timeout —
   30 seconds by default, extendable per command by an explicit positive override up to 24
   hours. It returns a `CompletionStage`, and when that stage completes, the dispatcher
   sends the terminal result.

Malformed or over-limit requests fail the same way every time and never execute user code.
Note the subtlety in the error path for step 1: a message so malformed that it has no
trustworthy `id` cannot be correlated to a promise, so the runtime drops it silently rather
than guessing which call to fail.

## Exactly one terminal result

Every accepted `invoke` produces **exactly one** terminal `result`, and never more. This is
enforced with a single atomic "terminal" flag per invocation: the success path, the failure
path, the timeout, and a cancel all race to set it, and only the winner sends a result. A
timeout that fires while the handler is completing, or a cancel that arrives just as the
result is ready, can never produce two answers to one call.

Results correlate by `id` and may complete out of order. The frontend does not care about
ordering: it looks each `result` up by `id`, resolves or rejects that one promise, and
discards a result whose `id` it no longer has pending (because that call already timed out,
was cancelled, or was dropped by a navigation reset).

## Cancellation and timeouts

Cancellation is **best-effort**, and there are two independent timers.

On the frontend, `invoke` accepts a client-side timeout (default 30 s) and an `AbortSignal`.
When either fires, the client sends a `cancel` envelope and rejects the promise locally
(with `TIMEOUT` or `CANCELLED`) without waiting for the runtime. On the Java side, a `cancel`
interrupts the worker virtual thread and sends exactly one terminal `result` with code
`CANCELLED`; the runtime also enforces its own command timeout, which sends a terminal
`TIMEOUT` result regardless of what the client does.

"Best-effort" is the honest word here. Interrupting a virtual thread signals the handler,
but a handler that ignores interruption will keep running until it finishes or the timeout
fires — cancellation frees the *caller*, not necessarily the *work*. Handlers that do
cancellable work should observe `InvocationContext.isCancelled()` or respond to interruption.
Whatever the handler does, the terminal-result guarantee holds: the caller sees one answer.

## Events and backpressure

Events flow the other way: Java to the frontend, with no request and no reply. A handler or
the application emits a named event through an `EventEmitter`, and the runtime serializes it
as an `event` envelope and delivers it to the window. The frontend subscribes with
`on(name, handler)`.

Events from one emitter to one window preserve enqueue order, and the per-window event queue
is **bounded** — 256 events by default — with a configurable overflow policy. This is the
backpressure mechanism, and the choice of policy is a real trade-off:

- `REJECT` (the default) refuses a new event when the queue is full, surfacing the overload
  to the emitter as an error rather than silently losing data.
- `DROP_OLDEST` discards the oldest queued event to make room, favouring the freshest data
  over completeness — a good fit for progress or telemetry where only the latest value
  matters.
- `COALESCE` collapses events by name, so a burst of same-named updates becomes one.

The queue keeps the head event pending until the UI dispatcher confirms it was delivered,
so the bound covers work already submitted to the (asynchronous) UI thread, not just work
waiting to be submitted. That prevents a slow renderer from letting an unbounded backlog
accumulate behind an event that has been "sent" but not yet handed to the page.

Events are fire-and-forget by design. There is no per-event acknowledgement from the page,
because adding one would turn every event into a round trip and reintroduce the coupling the
asynchronous model exists to avoid. If you need a confirmed response, model it as a command.
See [Emit an event](../guides/emitting-events.md) for the practical patterns.

## Navigation reset

A navigation is a clean break. When the main frame commits a new document, the runtime
invalidates the old `NavigationSession`, mints a new nonce, rejects any new invoke bound to
the old session, cancels the old session's in-flight invocations, and drops its queued
events. The old document is gone and cannot consume a result, so for those cancelled
invocations the runtime deliberately sends *no* terminal result — there is nobody to receive
it — and releases their request ids immediately so a freshly loaded client (whose counter
commonly restarts at one) cannot collide with them.

The frontend mirrors this. When the client sees the nonce change, it knows a new session
began, so it rejects every pending promise locally with `NAVIGATION_RESET` and re-runs the
handshake lazily on the next invoke. The result is that late work from a previous document
never reaches the new one, on either side of the bridge — the same property the nonce
guarantees for inbound messages, applied to outbound results.

## Related reading

- [IPC protocol reference](../architecture/ipc-protocol.md) — the exact envelope shapes,
  error codes, and limits.
- [Threading and the event loop](./threading-and-the-event-loop.md) — why handlers run on
  virtual threads and how results marshal back.
- [Security model](./security-model.md) — why authorization happens on the Java side and
  before deserialization.
- [Define a command](../guides/defining-commands.md) and
  [Emit an event](../guides/emitting-events.md) — the how-to guides.
- The [verification matrix](../../VERIFICATION.md) records which platforms the bridge and
  its stress tests are proven on.
