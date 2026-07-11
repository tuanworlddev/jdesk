# Native memory and FFM

This page explains how JDesk calls native operating-system code without JNI, JNA, or Rust,
and how it keeps that boundary memory-safe. It covers Java's Foreign Function & Memory API,
the handle state machine that governs native resource lifetime, and the callback registry
and gate that make it safe to free native memory while callbacks may still be arriving. It
is for readers who want to understand the safety model and its trade-offs, not a how-to for
writing an adapter. You never touch this layer directly; the platform adapters do.

## Calling native code the JVM way

Every JDesk platform adapter has to drive a native UI toolkit — Win32 and WebView2, AppKit
and WKWebView, GTK and WebKitGTK. Historically the JVM reached native code through JNI, which
means writing and compiling C glue for every function. JDesk takes a different path: all
native interop uses `java.lang.foreign`, the Foreign Function & Memory API (FFM, JEP 454), so
there is **no JNI glue authored by this project, no JNA, and no Rust**
([ADR-001](../architecture/ADR-001-java25-jpms-ffm.md)). This is a founding decision: a Java
team can build, read, and debug the whole stack in Java, with no second toolchain.

FFM gives the adapters a few primitives, and it helps to know the vocabulary the rest of this
page uses:

- **Downcalls** — Java calling a native function. The adapter looks up a symbol with
  `SymbolLookup`, describes its signature with a `FunctionDescriptor`, and gets a
  `MethodHandle` it can invoke. On macOS, for example, `dispatch_async_f` and
  `pthread_main_np` are bound exactly this way.
- **Upcall stubs** — native code calling *back* into Java. The adapter binds a Java method to
  a native function pointer with `Linker.upcallStub(...)`. This is how a WebView delivers a
  bridge message or a navigation decision to Java: the native engine invokes a function
  pointer that lands in a Java method.
- **Arenas** — the lifetime scope for native memory and upcall stubs. An `Arena` owns
  segments and stubs and frees them when it closes. A *confined* arena is used and closed on
  one thread for a bounded operation (streaming an asset body chunk by chunk, say); a
  *shared* arena outlives a single call and is closed explicitly by whoever owns it.

FFM also removes an entire class of legacy hazard by policy: `sun.misc.Unsafe` is forbidden,
and native access is granted only to the platform modules and `dev.jdesk.ffm` through
`--enable-native-access`, never to `ALL-UNNAMED` in a production image. The privilege to
touch native memory is scoped to the modules that need it.

## The safety problem this layer exists to solve

The moment you can call native code, you inherit its failure modes. Two dominate at a native
boundary, and both corrupt memory rather than throwing a clean exception:

- **Use-after-free** — Java calls a native function through a handle whose underlying
  resource has already been released, or native code invokes an upcall stub whose backing
  arena has already been freed.
- **Double-free** — a resource is released twice, once by an explicit close and again by some
  other path.

A garbage collector does not help here, because the objects in question are native and their
lifetime is not the JVM's to manage. JDesk's answer is not to hope these never happen but to
make the *timing* of native release deterministic and to reject any operation that arrives
outside the safe window. Two cooperating mechanisms do this: a handle state machine for the
resources you own, and a callback registry plus gate for the callbacks native code owns.

## The handle state machine

Every native handle wrapper extends `NativeHandle`, which enforces a four-state machine —
`NEW → OPEN → CLOSING → CLOSED` — with atomic transitions:

- A handle starts `NEW`. After the native resource is acquired, the wrapper transitions it to
  `OPEN`.
- Every native operation is guarded by `requireOpen()`, which throws `ALREADY_CLOSED` unless
  the handle is `OPEN`. An operation on a handle that is closing or closed fails cleanly with
  an exception instead of dereferencing a freed resource.
- `close()` moves `OPEN → CLOSING`, runs the actual native release exactly once, then sets
  `CLOSED`. If the handle was never opened, close goes straight to `CLOSED` with nothing to
  release.

Two properties fall out of this, and both are deliberate. **Close is idempotent**: a second
`close()` finds the handle already past `OPEN` and returns without doing anything, so
double-free is structurally impossible. And **release runs exactly once**, from `close()`
only. There is intentionally no cleaner or finalizer fallback — owners close explicitly. That
is a considered trade-off: finalizer-driven release runs at an unpredictable time on a GC
thread, which is exactly the non-determinism this layer is trying to eliminate. JDesk prefers
a leak that is visible and diagnosable over a free that happens at an uncontrolled moment. The
[verification matrix](../../VERIFICATION.md) records stress and leak runs that exercise this
lifecycle across repeated window cycles.

## Callbacks are the hard case

The handle state machine covers resources Java calls *into*. Upcall stubs are the reverse:
native code calls *out* to Java, on the native engine's schedule, and it may do so at a
moment Java considers the resource gone. Consider a WebView being torn down while its render
process still has a queued message to deliver, or a navigation decision in flight. If Java
frees the arena backing that upcall stub and the native engine then invokes it, the process
jumps into freed memory. Freeing native memory while a callback might still fire is the
central hazard, and it needs more than an idempotent close.

JDesk handles it with two pieces: the `NativeCallbackRegistry`, which pins everything a
callback needs to stay alive, and the `CallbackGate`, which controls the window in which
callbacks may run.

### Pinning: the callback registry

An upcall stub is only safe to invoke while every object it depends on is still alive: the
Java target, the `MethodHandle` bound into it, the native stub segment itself, the owning
arena, and the platform's own registration token. Drop any of those and the callback becomes
a dangling pointer. `NativeCallbackRegistry` **strongly retains all of them** for every
registration, so nothing they need can be collected or freed while the registration stands.
It also owns the shared arena for the stubs it pins — that arena is closed by the registry
and by nothing else, so ownership is unambiguous.

Unregistration happens in **reverse registration order**, the mirror of construction, so
dependencies are torn down after the things that depend on them. A detach that throws is
logged and does not abort the remaining detaches — teardown must run to completion even when
one step misbehaves.

### Gating: the callback gate

Pinning keeps the pieces alive; the `CallbackGate` decides *when a callback is allowed to
run at all*. Every upcall body runs inside `enter()` / `exit()`. While the gate is open,
`enter()` increments an in-flight counter and returns `true`, and the body runs; `exit()`
decrements it. When the owner wants to shut down, `closeAndAwaitQuiescence(timeout)` closes
the gate — after which `enter()` returns `false` — and then waits for the in-flight count to
drain to zero.

The adapters use this at the top of every upcall body. In the macOS WebView, for instance,
each Objective-C callback — a bridge message, an asset request, a navigation decision, a
process-termination notice — begins by checking `registry.gate().enter()` and returns
immediately if it is `false`. A callback that arrives after the gate has closed is **rejected
safely**: it returns without touching any resource, instead of dereferencing memory that is
about to be freed.

### Why callbacks are never freed under a live call

The two mechanisms compose into the ordering that makes teardown safe. Closing the registry:

1. Detaches every native registration in reverse order, so the platform stops calling the
   stubs.
2. Closes the gate and waits for in-flight callbacks to drain — new callbacks are already
   rejected, and existing ones are given time to finish.
3. Closes the arena **only after** the gate confirms quiescence.

The interesting case is the one where a callback does *not* drain in time. If the wait times
out — a straggler is still running after the quiescence timeout — the registry deliberately
**leaks the arena and logs it**, rather than closing it. Freeing memory out from under a live
callback would corrupt the process; leaking a bounded amount of memory will not. Given the
choice, JDesk always prefers the leak. This is the honest core of the design: it does not
promise that a misbehaving native call can never overstay, only that JDesk will never free
memory while one is in flight.

## Arena ownership rules

The thread that ties this together is single, explicit ownership. A shared arena has exactly
one owner responsible for closing it, and that owner closes it only after the callbacks it
backs can no longer run. Some stubs are never freed at all by design: a dispatcher trampoline
that libdispatch or GLib might still invoke at application teardown lives in a
process-lifetime arena, because a queued dispatch item could outlive any narrower scope, and
the cost of never freeing one small stub is trivial next to the cost of freeing it too early.
Confined arenas, by contrast, are scoped tightly to a single bounded operation and closed as
soon as it completes.

The rule underneath all of it: native memory is freed only when it is provably unreachable
from native code — never on a hope, never on a finalizer's schedule, and never while a call
that could touch it is still running.

## Related reading

- [Threading and the event loop](./threading-and-the-event-loop.md) — the UI-thread rules
  the adapters observe when they call native toolkits.
- [Architecture overview](../architecture/overview.md) — where `jdesk-native-ffm` and the
  adapters sit in the module map.
- [ADR-001](../architecture/ADR-001-java25-jpms-ffm.md) — the decision to use JDK 25, JPMS,
  and FFM, and to forbid JNI, JNA, Rust, and `Unsafe`.
- The [verification matrix](../../VERIFICATION.md) — the native-smoke and stress/leak
  evidence, by platform.
