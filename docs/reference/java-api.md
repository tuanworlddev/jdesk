# Java API reference

The public Java API surface of JDesk, module `dev.jdesk.api`. Every type here is
dependency-free and stable; the runtime (`dev.jdesk.runtime`) binds to it through
[`JDeskBootstrap`](#jdeskbootstrap). Types are grouped by concern. For task walkthroughs
see [Getting started](../getting-started/introduction.md); for wire behavior see the
[IPC protocol](../architecture/ipc-protocol.md).

Signatures are copied from the source. Where a member throws
[`JDeskException`](#jdeskexception), the [`ErrorCode`](#errorcode) is named; see the
[error codes reference](error-codes.md) for meanings.

## Application and bootstrap

### `JDeskApplication`

`dev.jdesk.api.JDeskApplication` — application entry point. Final, non-instantiable;
obtain a [`Builder`](#jdeskapplicationbuilder) from the static factory.

| Member | Meaning |
| --- | --- |
| `static JDeskApplication.Builder builder()` | Creates a new builder. |

### `JDeskApplication.Builder`

`dev.jdesk.api.JDeskApplication.Builder` — fluent builder for an
[`ApplicationSpec`](#applicationspec). All setters return `this`.

| Member | Meaning |
| --- | --- |
| `Builder id(String id)` | Sets the application id (required). Not-null. |
| `Builder commands(CommandRegistry commands)` | Sets the command registry. Default: `CommandRegistry.of()` (empty). |
| `Builder capabilities(CapabilitySet capabilities)` | Sets the capability set. Default: `CapabilitySet.empty()`. |
| `Builder window(WindowConfig window)` | Adds a window. Callable repeatedly; order preserved. |
| `Builder lifecycle(LifecycleListener listener)` | Adds a lifecycle listener. Callable repeatedly. |
| `Builder devServerUrl(String url)` | Development-only exact origin, e.g. `http://127.0.0.1:5173`. |
| `ApplicationSpec buildSpec()` | Validates and builds the spec. Throws `INVALID_REQUEST` if `id` is null. |
| `int run(String[] args)` | Builds the spec, loads the single `JDeskBootstrap` via `ServiceLoader`, and runs until shutdown; returns the process exit code. Throws `ILLEGAL_STATE` if the number of `JDeskBootstrap` providers is not exactly one. Passing `null` args is treated as an empty array. |

### `ApplicationSpec`

`dev.jdesk.api.ApplicationSpec` — immutable record of everything the builder collected,
handed to the runtime bootstrap.

```java
record ApplicationSpec(
    String id,
    CommandRegistry commands,
    CapabilitySet capabilities,
    List<WindowConfig> windows,
    List<LifecycleListener> lifecycleListeners,
    Optional<String> devServerUrl)
```

| Component | Meaning |
| --- | --- |
| `id()` | Reverse-DNS application id, matching `[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z][a-zA-Z0-9-]*)+`. |
| `commands()` | The command registry. |
| `capabilities()` | The capability set. |
| `windows()` | Immutable copy of the window configs. |
| `lifecycleListeners()` | Immutable copy of the lifecycle listeners. |
| `devServerUrl()` | Optional development origin. |

Construction throws `INVALID_REQUEST` when: `id` does not match the reverse-DNS pattern;
`windows` is empty; or two windows share the same [`WindowId`](#windowid).

### `ApplicationHandle`

`dev.jdesk.api.ApplicationHandle` — thread-safe control plane for a running application.
Available from [`InvocationContext.application()`](#invocationcontext) and
[`LifecycleListener.onReady(ApplicationHandle)`](#lifecyclelistener).

| Member | Meaning |
| --- | --- |
| `CompletionStage<WindowHandle> openWindow(WindowConfig config)` | Opens a native window, reserving its id before UI-thread creation starts. |
| `Optional<WindowHandle> window(WindowId windowId)` | Looks up a currently open window. |
| `PlatformInfo platform()` | The running platform description. |
| `UiDispatcher ui()` | The UI-thread dispatcher. |
| `void requestStop()` | Requests orderly application shutdown without blocking the caller. |

### `WindowHandle`

`dev.jdesk.api.WindowHandle` — thread-safe public handle for one open native window. All
mutating operations return a `CompletionStage<Void>` that completes on the UI thread.

| Member | Meaning |
| --- | --- |
| `WindowId id()` | The window id. |
| `EventEmitter events()` | Emitter targeting this window. |
| `CompletionStage<Void> show()` | Shows the window. |
| `CompletionStage<Void> hide()` | Hides the window. |
| `CompletionStage<Void> focus()` | Focuses the window. |
| `CompletionStage<Void> setTitle(String title)` | Sets the title. |
| `CompletionStage<Void> setBounds(int x, int y, int width, int height)` | Sets position and size. |
| `CompletionStage<Void> setMinimized(boolean minimized)` | Minimizes/restores. |
| `CompletionStage<Void> setMaximized(boolean maximized)` | Maximizes/restores. |
| `CompletionStage<Void> setFullscreen(boolean fullscreen)` | Enters/leaves fullscreen. |
| `CompletionStage<Void> setAlwaysOnTop(boolean alwaysOnTop)` | Toggles always-on-top. |
| `CompletionStage<Void> close()` | Closes the window. |

### `JDeskBootstrap`

`dev.jdesk.api.JDeskBootstrap` — internal service binding the API to the runtime.
Provided by `dev.jdesk.runtime`; applications never implement or call it directly.

| Member | Meaning |
| --- | --- |
| `int launch(ApplicationSpec spec, String[] args)` | Launches the runtime for the spec and returns the exit code. |

## Windows

### `WindowConfig`

`dev.jdesk.api.WindowConfig` — immutable configuration for one native window.

```java
record WindowConfig(
    WindowId id, String title, int width, int height, boolean resizable, URI entry)
```

Construction throws `INVALID_REQUEST` if `width` or `height` is outside `1..32767`.

### `WindowConfig.Builder`

`dev.jdesk.api.WindowConfig.Builder` — obtained from `WindowConfig.builder()`.

| Member | Meaning | Default |
| --- | --- | --- |
| `Builder id(String id)` | Sets the id; wraps the string in a [`WindowId`](#windowid) (validated). | required |
| `Builder title(String title)` | Sets the window title. | `""` |
| `Builder size(int width, int height)` | Sets width and height (pixels). | `800 × 600` |
| `Builder resizable(boolean resizable)` | Sets resizability. | `true` |
| `Builder entry(String entry)` | Sets the entry URL; parsed with `URI.create`. | required |
| `WindowConfig build()` | Builds the config. Throws `INVALID_REQUEST` if `id` or `entry` is unset. | — |

Bounds: `width`/`height` must be in `1..32767`; violations throw `INVALID_REQUEST` from
the `WindowConfig` constructor.

### `WindowId`

`dev.jdesk.api.WindowId` — stable window identifier.

```java
record WindowId(String value)
```

| Fact | Value |
| --- | --- |
| Grammar | `[a-zA-Z0-9._-]{1,64}` |
| Invalid or null value | throws `INVALID_REQUEST` |
| `toString()` | returns `value()` |

## Commands

Commands are discovered at compile time by the JDesk annotation processor, never by
runtime classpath scanning. See [Compile-time registration](../architecture/ADR-005-compile-time-registration.md).

### `@DesktopCommand`

`dev.jdesk.api.DesktopCommand` — marks a method as an IPC command.
`@Retention(CLASS)`, `@Target(METHOD)`.

| Attribute | Meaning |
| --- | --- |
| `String value()` | Wire name, e.g. `"greeting.greet"`: 1..128 chars, dot-separated lowerCamel segments. |

### `@RequiresCapability`

`dev.jdesk.api.RequiresCapability` — declares the [capability](capabilities-json.md)
required to invoke the command. Evaluated before payload deserialization.
`@Retention(CLASS)`, `@Target(METHOD)`.

| Attribute | Meaning |
| --- | --- |
| `String value()` | Required capability name. |

### `@PublicDesktopCommand`

`dev.jdesk.api.PublicDesktopCommand` — explicit opt-out of the capability requirement for
a command safe to expose to any window. `@Retention(CLASS)`, `@Target(METHOD)`; no
attributes.

Rule (deny by default): the annotation processor rejects any `@DesktopCommand` method
that has neither `@RequiresCapability` nor `@PublicDesktopCommand`.

### `CommandRegistry`

`dev.jdesk.api.CommandRegistry` — immutable registry of all commands, normally produced by
generated code (`GeneratedCommands.create(...)`).

| Member | Meaning |
| --- | --- |
| `static CommandRegistry of(CommandDefinition... definitions)` | Builds a registry. Throws `ILLEGAL_STATE` on a duplicate command name. |
| `Optional<CommandDefinition> find(String name)` | Looks up a command by wire name. |
| `Set<String> commandNames()` | All registered command names (insertion order). |
| `int size()` | Number of registered commands. |

### `CommandDefinition`

`dev.jdesk.api.CommandDefinition` — compile-time-generated command metadata plus its
handler.

```java
record CommandDefinition(
    String name,
    Optional<String> requiredCapability,
    Class<?> requestType,
    Optional<Duration> timeout,
    CommandHandler handler)
```

| Component | Meaning |
| --- | --- |
| `name()` | Wire name; must match `[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)*`, max 128 chars. |
| `requiredCapability()` | Empty only for `@PublicDesktopCommand` commands. |
| `requestType()` | DTO type the payload is deserialized to before the handler runs. |
| `timeout()` | Maximum duration; empty uses the runtime default (30 s). If present, must be `> 0` and `≤ 24 h`. |
| `handler()` | The [`CommandHandler`](#commandhandler). |

Construction throws `INVALID_REQUEST` on an invalid `name` or an out-of-range `timeout`.

### `CommandHandler`

`dev.jdesk.api.CommandHandler` — functional interface invoking one command with an
already-deserialized request DTO. Runs on a virtual thread, never on the native UI thread.

| Member | Meaning |
| --- | --- |
| `CompletionStage<?> invoke(Object request, InvocationContext context)` | Runs the command and completes with its result (or exceptionally). |

### `InvocationContext`

`dev.jdesk.api.InvocationContext` — per-invocation context passed to every handler.

| Member | Meaning |
| --- | --- |
| `WindowId windowId()` | The invoking window. |
| `String commandName()` | The command wire name. |
| `String requestId()` | Request id, unique within the navigation session. |
| `PlatformInfo platform()` | The running platform. |
| `ApplicationHandle application()` | Control plane for the running application. |
| `EventEmitter events()` | Emitter targeting the invoking window. |
| `boolean isCancelled()` | True once the client cancelled or the command timed out. |

## Capabilities

The capability model is deny-by-default. See the
[`jdesk-capabilities.json` reference](capabilities-json.md) for the file format.

### `CapabilitySet`

`dev.jdesk.api.CapabilitySet` — immutable, deny-by-default set of grants. Parsing from
`jdesk-capabilities.json` is provided by the runtime
(`dev.jdesk.runtime.config.Capabilities.fromResource`).

| Member | Meaning |
| --- | --- |
| `boolean isGranted(String capability, WindowId windowId)` | True when the capability is granted to the window. |
| `Set<CapabilityGrant> grants()` | All grants. |
| `static CapabilitySet of(Collection<CapabilityGrant> grants)` | Builds a set from grants. |
| `static CapabilitySet empty()` | An empty set (grants nothing). |

### `CapabilityGrant`

`dev.jdesk.api.CapabilityGrant` — one granted capability.

```java
record CapabilityGrant(String capability, Set<String> windows)
```

| Member | Meaning |
| --- | --- |
| `capability()` | Capability name; must be non-blank and ≤ 128 chars (else `INVALID_REQUEST`). |
| `windows()` | Window ids the grant applies to; empty means every window. |
| `static CapabilityGrant forAllWindows(String capability)` | Grant with an empty window set. |

### `PermissionDecision`

`dev.jdesk.api.PermissionDecision` — result of a capability evaluation.

```java
record PermissionDecision(boolean allowed, ErrorCode errorCode, String publicReason)
```

| Member | Meaning |
| --- | --- |
| `allowed()` | Whether the invocation is permitted. |
| `errorCode()` | The `ErrorCode` to report when denied. |
| `publicReason()` | Frontend-safe reason; must not reveal which capabilities exist or are configured. |
| `static PermissionDecision allow()` | A shared allowed decision. |
| `static PermissionDecision deny(ErrorCode code, String publicReason)` | A denied decision. |

## Events

### `EventEmitter`

`dev.jdesk.api.EventEmitter` — emits Java-to-JavaScript events. Events to one window from
one emitter preserve enqueue order; queues are bounded.

| Member | Meaning |
| --- | --- |
| `void emit(String eventName, Object payload)` | Emits an event. `eventName`: 1..128 chars, same grammar as command names. `payload` is serialized with the configured JSON codec and may be null. Throws `LIMIT_EXCEEDED` when the target queue is full and the overflow policy is `REJECT`. |

### `Subscription`

`dev.jdesk.api.Subscription` — handle for an event/listener registration. Functional
interface extending `AutoCloseable`.

| Member | Meaning |
| --- | --- |
| `void close()` | Cancels the registration; idempotent. |

## Lifecycle

### `LifecycleListener`

`dev.jdesk.api.LifecycleListener` — application lifecycle hooks. All methods default to
no-ops (except `onCloseRequested`, which defaults to allowing the close).

| Member | Meaning |
| --- | --- |
| `default void onStarting()` | Application is starting. |
| `default void onReady()` | Application is ready. |
| `default void onReady(ApplicationHandle application)` | Ready, with the control handle; the default delegates to `onReady()`. |
| `default boolean onCloseRequested(WindowId windowId)` | Return `false` to veto the close request; default returns `true`. |
| `default void onStopping()` | Application is stopping. |
| `default void onStopped()` | Application has stopped. |

### `LifecycleState`

`dev.jdesk.api.LifecycleState` — lifecycle states. Transitions are strictly forward.

| Constant | Order |
| --- | --- |
| `NEW` | 1 |
| `STARTING` | 2 |
| `READY` | 3 |
| `STOPPING` | 4 |
| `STOPPED` | 5 |

## Threading

### `UiDispatcher`

`dev.jdesk.api.UiDispatcher` — marshals work onto the native UI thread. Window and WebView
objects are created, called, and destroyed only on their UI thread. Never block the UI
thread.

| Member | Meaning |
| --- | --- |
| `boolean isUiThread()` | True when the caller is on the UI thread. |
| `void execute(Runnable action)` | Runs `action` on the UI thread (fire-and-forget). |
| `<T> CompletionStage<T> submit(Callable<T> action)` | Runs `action` on the UI thread and completes with its result. |
| `void assertUiThread()` | Throws `ILLEGAL_STATE` when called off the UI thread in development/test mode; logs and fails safe in production. |

## Errors

### `JDeskException`

`dev.jdesk.api.JDeskException` — structured framework exception, extends
`RuntimeException`.

| Member | Meaning |
| --- | --- |
| `JDeskException(ErrorCode code, String publicMessage)` | Constructs with a code and frontend-safe message. |
| `JDeskException(ErrorCode code, String publicMessage, Throwable cause)` | As above, with a cause. |
| `ErrorCode code()` | The error code. |
| `String publicMessage()` | The only message that may be sent to the frontend; must never contain secrets, paths, SQL, or internal detail. |

### `ErrorCode`

`dev.jdesk.api.ErrorCode` — the only error identifiers that may cross the IPC boundary to
the frontend. The full enumeration, when each occurs, and which reach the frontend are in
the [error codes reference](error-codes.md).

## Platform

### `PlatformInfo`

`dev.jdesk.api.PlatformInfo` — immutable description of the running platform.

```java
record PlatformInfo(String osName, String osVersion, String architecture)
```

| Member | Meaning |
| --- | --- |
| `osName()` | Operating-system name. |
| `osVersion()` | Operating-system version. |
| `architecture()` | CPU architecture. |

## Versioning

### `JDeskVersion`

`dev.jdesk.api.JDeskVersion` — framework version constants.

| Member | Meaning |
| --- | --- |
| `static final int PROTOCOL_VERSION` | Independent integer IPC protocol version. Currently `1`. |

## Binary streaming

### `BinaryStream`

`dev.jdesk.api.BinaryStream` — a lazily opened binary response streamed to the renderer
with pull backpressure. Returned from a command handler to stream bytes.

| Member | Meaning |
| --- | --- |
| `BinaryStream(long length, String contentType, String fileName, Source source)` | Constructs a stream; `length` must be ≥ 0. |
| `static BinaryStream of(Path path, String contentType) throws IOException` | Streams a file; derives length and file name from the normalized path. |
| `long length()` | Declared content length in bytes. |
| `String contentType()` | MIME content type. |
| `String fileName()` | Suggested file name. |
| `Source source()` | The lazy byte source. |

`BinaryStream.Source` — functional interface: `InputStream open() throws IOException`.

## See also

- [IPC protocol](../architecture/ipc-protocol.md) — the wire envelopes and processing order.
- [Error codes](error-codes.md) — every `ErrorCode` and where it surfaces.
- [`jdesk-capabilities.json`](capabilities-json.md) — the capability file format.
- [CLI reference](cli.md) — scaffolding and building projects.
- [Verification status](../../VERIFICATION.md) — what is verified, and on which platforms.
