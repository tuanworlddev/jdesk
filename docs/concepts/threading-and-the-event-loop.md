# Threading and the event loop

This page explains JDesk's threading model: the single UI thread each platform demands, the
`UiDispatcher` that marshals work onto it, why your commands run on virtual threads and never
on the UI thread, and the rules that keep the two from deadlocking. It is for readers who
want to understand *why* the model is shaped this way. For the asynchronous message flow it
supports, see [How IPC works](./how-ipc-works.md).

## Every platform has one UI thread

The hard constraint JDesk builds around is not a JVM rule — it is an operating-system rule.
Each native windowing system requires that its windows, WebViews, and event handling live on
one specific thread, and that thread runs an event loop that must keep turning
([ADR-001](../architecture/ADR-001-java25-jpms-ffm.md), spec section 7). The details differ
per adapter, but the shape is identical:

- **Windows (`windows-webview2`)** — Win32 with a single-threaded apartment (STA). The UI
  thread pumps the Win32 message queue; window and WebView2 COM objects are affined to it.
- **macOS (`macos-wkwebview`)** — AppKit requires the *process main thread*. `NSApplication`
  and AppKit event handling run on the first thread, which is why a JDesk app on macOS must
  launch the JVM with `-XstartOnFirstThread`. Windows and `WKWebView`s are created and called
  there.
- **Linux (`linux-webkitgtk`)** — GTK 3 and WebKitGTK run on their GLib main context. The UI
  thread is the one that ran `gtk_init_check` and runs `gtk_main`.

In every case, touching a window or WebView off that thread is undefined behavior at the OS
level. The framework's job is to make that thread easy to reach safely and hard to reach
unsafely, and to make sure real work never runs on it.

## The UiDispatcher contract

The abstraction that hides these three mechanisms behind one interface is
[`UiDispatcher`](../../modules/jdesk-api/src/main/java/dev/jdesk/api/UiDispatcher.java) in
`jdesk-api`. It is small on purpose:

```java
public interface UiDispatcher {
    boolean isUiThread();
    void execute(Runnable action);
    <T> CompletionStage<T> submit(Callable<T> action);
    void assertUiThread();
}
```

- `isUiThread()` reports whether the caller is already on the UI thread.
- `execute(Runnable)` runs the action on the UI thread. If the caller is already there it
  runs inline; otherwise it is enqueued and the platform's event loop drains it.
- `submit(Callable)` does the same but returns a `CompletionStage` for the result, so a
  caller on another thread can compose on the outcome without blocking.
- `assertUiThread()` is a guardrail: in development and test mode it throws
  `JDeskException` with `ILLEGAL_STATE` when called off the UI thread, so a threading bug
  fails loudly; in production it logs and fails safe rather than crashing the app.

Each adapter implements this contract with its native primitive. On macOS,
`MacUiDispatcher` enqueues the runnable and posts a trampoline onto the main queue with
`dispatch_async_f` (the public symbol behind `dispatch_get_main_queue()`), and answers
`isUiThread()` with `pthread_main_np`. On Windows, `WindowsUiDispatcher` owns a hidden
message-only window and posts `WM_APP`; its window procedure drains the queue on the UI
thread — which, unlike posting a thread message, still delivers correctly inside nested
modal message loops. On Linux, `LinuxUiDispatcher` posts a one-shot `GSourceFunc` onto the
default main context with `g_main_context_invoke_full`, a documented thread-safe GLib API.
Three very different mechanisms, one contract; the runtime is written against the contract
and never against a platform.

## Why commands never run on the UI thread

Your `@DesktopCommand` handlers run on **virtual threads**, never on the UI thread
([ADR-006](../architecture/ADR-006-async-message-passing.md)). This is the single most
important threading rule in JDesk, and the reason is direct: the UI thread's event loop is
what keeps the window painting and responding to input. Any work that occupies it — a slow
computation, a blocking read, a database round trip — freezes the whole window for exactly
as long as it runs. An application that did its work on the UI thread would stutter under
its own logic.

So the dispatcher splits the work. When a bridge message arrives, the platform delivers it
to the runtime *on the UI thread*, and the dispatcher runs only the cheap, bounded, non-
blocking part there: it parses the envelope, checks the nonce, verifies the handshake and
request id, looks up the command, and evaluates the capability. All of that is fixed-cost
work with no I/O and no user code. Then it hands the actual handler to a
virtual-thread-per-task executor and returns, freeing the UI thread immediately. The handler
— your code, its deserialization, and whatever it does — runs entirely off the UI thread.

Virtual threads make this cheap. Because the framework's own concurrency is I/O-bound
message handling, a virtual thread per invocation costs almost nothing even with the
in-flight limit set to its default of 128 per window, and a handler that blocks on I/O
parks its virtual thread without tying up an OS thread. This is a large part of why JDesk
targets JDK 25+: the concurrency model that makes "one lightweight thread per command" the
obvious default is a platform feature, not a library the framework has to ship. CPU-bound
work is the exception the model calls out — it belongs on a bounded executor, not on an
unbounded fan-out of virtual threads competing for cores.

## Marshalling responses back

A command finishes on a virtual thread, but the result has to be posted to the WebView,
which can only be touched on the UI thread. So the return trip is the mirror of the outbound
one: the dispatcher's responder wraps `postJson` in `UiDispatcher.submit(...)`, hopping back
onto the UI thread to hand the serialized `result` envelope to the WebView. The heavy work
happened off the UI thread; only the final, cheap "post this string to the page" touches it.

Events take the same path. The per-window event queue delivers each serialized `event`
through the UI dispatcher and keeps the head event pending until that delivery confirms, so
a slow UI thread applies backpressure to the emitter instead of letting a backlog build
(see [How IPC works](./how-ipc-works.md#events-and-backpressure)).

## The rules

The model reduces to a few rules. They are not style preferences — breaking them either
corrupts native state or deadlocks the app:

- **Never run user code, I/O, or blocking work on the UI thread.** That is what virtual
  threads are for. The UI thread does message copying, cheap validation, and posting
  responses — nothing that can block.
- **Never call `join()`, `get()`, or any blocking wait on the UI thread.** Blocking the UI
  thread stops the event loop, and if the thing you are waiting for needs the UI thread to
  make progress, you have deadlocked.
- **Only touch windows and WebViews on the UI thread.** Reach them through `UiDispatcher`
  (`execute`/`submit`). The `ApplicationHandle` operations that manipulate windows already
  do this for you.
- **Do not assume ordering between the UI thread and a handler.** They run concurrently;
  results correlate by id and may complete out of order.

## Avoiding deadlock

The classic desktop deadlock is a two-way wait: the UI thread blocks waiting for a
background task, while that task blocks waiting to run something on the UI thread. Neither
can proceed. JDesk's structure makes this pattern hard to write by accident, because the
framework itself never blocks the UI thread on a command — the outbound direction hands off
to a virtual thread and returns, and the inbound direction (`submit`) returns a
`CompletionStage` you compose on rather than a value you wait for.

You can still create a deadlock by hand: if handler code calls `submit(...)` to run
something on the UI thread and then *blocks* waiting for that stage, and it happens to be
running in a context where the UI thread is itself blocked, everything stops. The rule that
prevents it is the same as above — from a handler, compose on the returned
`CompletionStage` instead of blocking on it. The `assertUiThread()` guardrail catches the
inverse mistake, where code that must be on the UI thread is not, and turns it into a loud
failure in development instead of intermittent native corruption in production.

The single-UI-thread model is a constraint every native desktop toolkit shares; what JDesk
adds is a uniform dispatcher over three platforms and a default of running application logic
on virtual threads, so the UI thread stays free and the deadlock-prone patterns are the ones
you have to go out of your way to write.

## Related reading

- [How IPC works](./how-ipc-works.md) — the asynchronous message flow this threading model
  serves.
- [Native memory and FFM](./native-memory-and-ffm.md) — how the adapters call the native UI
  toolkits safely across the FFM boundary.
- [ADR-001](../architecture/ADR-001-java25-jpms-ffm.md) and
  [ADR-006](../architecture/ADR-006-async-message-passing.md) — the decisions behind JDK 25,
  FFM, and asynchronous message passing.
- The [verification matrix](../../VERIFICATION.md) records which platforms the threading and
  stress behavior are proven on.
