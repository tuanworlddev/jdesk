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
| `Builder frontendEvents(CommandRegistry events)` | Event definitions accepted from JavaScript (`frontendEvent` envelopes). Default: empty. |
| `Builder devServerUrl(String url)` | Development-only exact origin, e.g. `http://127.0.0.1:5173`. |
| `Builder contentSecurityPolicy(String csp)` | Replaces the default strict CSP on every `jdesk://app/` response (e.g. to allow `media-src https:`). Blank throws `INVALID_REQUEST`. Production launches screen `'unsafe-*'` via `CspValidator` unless `-Djdesk.security.acknowledgeUnsafeCsp=true`. See [Serving assets](../guides/serving-assets.md). |
| `Builder contentSecurityPolicy(Csp csp)` | As above, from a per-directive [`Csp`](#csp) builder so one directive can be widened without retyping the whole policy. |
| `Builder assetRoute(String prefix, AssetRoute route)` | Registers a Java-served asset route under `jdesk://app/<prefix>/...` (see [`AssetRoute`](#assetroute)). Prefix must match `[a-z0-9-]` segments; duplicates throw `INVALID_REQUEST`. |
| `Builder singleInstance(Consumer<List<String>> activationHandler)` | Enforces one running process per application id; later launches deliver their args to the handler. |
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
    Optional<String> devServerUrl,
    CommandRegistry frontendEvents,
    boolean singleInstance,
    Consumer<List<String>> activationHandler,
    Optional<String> contentSecurityPolicy,
    Map<String, AssetRoute> assetRoutes)
```

| Component | Meaning |
| --- | --- |
| `id()` | Reverse-DNS application id, matching `[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z][a-zA-Z0-9-]*)+`. |
| `commands()` | The command registry. |
| `capabilities()` | The capability set. |
| `windows()` | Immutable copy of the window configs. |
| `lifecycleListeners()` | Immutable copy of the lifecycle listeners. |
| `devServerUrl()` | Optional development origin. |
| `frontendEvents()` | Event definitions accepted from JavaScript. |
| `singleInstance()` / `activationHandler()` | Single-instance enforcement and its activation callback. |
| `contentSecurityPolicy()` | Optional CSP override (blank rejected). |
| `assetRoutes()` | App-defined asset routes keyed by prefix. |

Shorter convenience constructors default the trailing components (empty registry, no
single-instance, no CSP override, no routes).

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
| `SecretStore secrets()` | OS-backed secret storage scoped to this application id (see [`SecretStore`](#secretstore)). |
| `CompletionStage<Void> openExternal(URI uri)` | Opens an HTTP(S) URI in the OS default browser; rejects other schemes and credentials. |
| `CompletionStage<String> readClipboardText()` | Reads the system clipboard as text. |
| `CompletionStage<Void> writeClipboardText(String text)` | Writes clipboard text (max 1 MiB). |
| `CompletionStage<SystemTheme> systemTheme()` | The OS light/dark appearance (`SystemTheme.LIGHT`/`DARK`). |
| `CompletionStage<Optional<byte[]>> readClipboard(String type)` | Reads binary clipboard data of a type/UTI (e.g. `"public.png"`); empty when absent. |
| `CompletionStage<Void> writeClipboard(String type, byte[] data)` | Writes binary clipboard data under a type/UTI (max 64 MiB). |
| `CompletionStage<Void> setDockBadge(String label)` | Sets the Dock badge label, or clears it when blank. |
| `CompletionStage<Void> setApplicationMenu(MenuSpec menu, Consumer<String> onAction)` | Installs the app menu bar; a chosen `MenuItem.Action` reports its id to `onAction`. macOS only; no-op elsewhere. `MenuSpec.of(...)`, `MenuItem.submenu/action/separator`, accelerators like `"CmdOrCtrl+S"`. |
| `CompletionStage<Void> setApplicationIcon(byte[] pngData)` | Sets the application (Dock) icon from PNG bytes. macOS. |
| `CompletionStage<TrayHandle> createTrayItem(TraySpec spec, Consumer<String> onAction)` | Adds a status-bar/tray item with a click menu; a chosen action reports its id to `onAction`. macOS. |
| `CompletionStage<Subscription> registerGlobalShortcut(String accelerator, Runnable callback)` | Registers an OS-wide hotkey (>=1 modifier) that runs `callback` on the UI thread; close the `Subscription` to unregister. macOS (Carbon). |
| `CompletionStage<Void> showNotification(String title, String body)` | Posts a desktop notification; fails `ILLEGAL_STATE` where unavailable. Production display needs a signed bundle. macOS. |
| `CompletionStage<MessageDialogResult> showMessageDialog(MessageDialog dialog)` | Shows a native message dialog. |
| `CompletionStage<FileDialogResult> showOpenDialog(FileDialog.OpenDialog dialog)` | Shows a native, app-modal open dialog (see [Dialogs & printing](../guides/dialogs-and-printing.md)). |
| `CompletionStage<FileDialogResult> showSaveDialog(FileDialog.SaveDialog dialog)` | Shows a native, app-modal save dialog. |
| `CompletionStage<Void> printFile(PrintJob job)` | Sends a document file (typically a PDF) to a printer via the OS print system. |
| `FileWatchHandle watchFiles(Path root, FileWatchOptions options, Consumer<List<FileWatchEvent>> listener)` | Watches a directory for changes; coalesced batches delivered off the UI thread (see [File watching](#file-watching)). |
| `PtyHandle openPty(PtySpec spec, Consumer<byte[]> output)` | Starts a child process on a real pseudo-terminal; output streamed off the UI thread (see [Pseudo-terminals](#pseudo-terminals)). |
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
| `CompletionStage<Void> print()` | Opens the OS print dialog for this window's current page. |
| `CompletionStage<Optional<String>> showContextMenu(MenuSpec menu)` | Pops up a native context menu (modal) and completes with the chosen action id, or empty. macOS. |
| `CompletionStage<Subscription> onFileDrop(Consumer<List<Path>> listener)` | Delivers absolute paths of files dropped on the window (which HTML5 cannot expose); HTML5 DnD still works. macOS. |
| `CompletionStage<Void> close()` | Closes the window. |

Deep links and file associations are registered at packaging time — `jdesk { deepLink { schemes … }; appIcon; fileAssociation(ext, mime, description) }` (see the packaging guide); a `scheme://` link delivered while running reaches the single-instance activation handler.

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
    WindowId id, String title, int width, int height, boolean resizable, URI entry,
    int minWidth, int minHeight, boolean startMaximized, boolean rememberBounds,
    Optional<Position> position, WebViewSessionConfig webViewSession)
```

Construction throws `INVALID_REQUEST` if `width` or `height` is outside `1..32767`, if a
minimum is outside `0..32767`, or if a minimum exceeds the initial size. A 6-argument
convenience constructor defaults the trailing components (no minimum, not maximized, no
persistence).

### `WindowConfig.Builder`

`dev.jdesk.api.WindowConfig.Builder` — obtained from `WindowConfig.builder()`.

| Member | Meaning | Default |
| --- | --- | --- |
| `Builder id(String id)` | Sets the id; wraps the string in a [`WindowId`](#windowid) (validated). | required |
| `Builder title(String title)` | Sets the window title. | `""` |
| `Builder size(int width, int height)` | Sets width and height (pixels). | `800 × 600` |
| `Builder minSize(int minWidth, int minHeight)` | Minimum content size, enforced for user **and** programmatic resizes (0 = none). | `0 × 0` |
| `Builder position(int x, int y)` | Initial top-left position (logical coords); useful for placing windows side by side. Remembered bounds win over it. | OS-placed |
| `Builder resizable(boolean resizable)` | Sets resizability. | `true` |
| `Builder startMaximized(boolean startMaximized)` | Opens the window maximized. | `false` |
| `Builder rememberBounds(boolean rememberBounds)` | Persists size/position across runs (per app id and window id, under `~/.jdesk/window-state/`, overridable via `-Djdesk.state.dir=`) and restores them on open. | `false` |
| `Builder entry(String entry)` | Sets the entry URL; parsed with `URI.create`. | required |
| `Builder webViewSession(WebViewSessionConfig session)` | Selects the named persistent/private browser session. | `WebViewSessionConfig.DEFAULT` |
| `WindowConfig build()` | Builds the config. Throws `INVALID_REQUEST` if `id` or `entry` is unset. | — |

Bounds: `width`/`height` must be in `1..32767`; violations throw `INVALID_REQUEST` from
the `WindowConfig` constructor.

### `WebViewSessionConfig`

`dev.jdesk.api.WebViewSessionConfig` identifies browser state shared by windows in one application.
Use `persistent(id)` to request reuse of site data on later launches or `privateSession(id)` to
discard it at application shutdown. The builder optionally accepts a complete user-agent override.
Session ids follow `[a-zA-Z0-9._-]{1,64}`; unsafe ids and control characters in user agents throw
`INVALID_REQUEST`.

Windows and Linux support named persistent profiles. WKWebView rejects persistent DOM storage for
the custom `jdesk://` origin, so macOS named persistent sessions fail before creating a window;
private sessions support in-process DOM storage. Cookie/cache/proxy and download lifecycle methods
are not public yet; see the roadmap rather than depending on adapter internals.

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
| `JDeskException(ErrorCode code, String publicMessage, Object details, Throwable cause)` | As above, plus public-safe structured error data (any JSON-serializable value) delivered to the frontend as `error.data`. |
| `ErrorCode code()` | The error code. |
| `String publicMessage()` | The only message that may be sent to the frontend; must never contain secrets, paths, SQL, or internal detail. |
| `Object details()` | Structured, public-safe error data for the frontend (`error.data`); may be null. `jdesk-client` exposes it as `JDeskError.data`. |

### `ErrorCode`

`dev.jdesk.api.ErrorCode` — the only error identifiers that may cross the IPC boundary to
the frontend. The full enumeration, when each occurs, and which reach the frontend are in
the [error codes reference](error-codes.md).

## Assets

### `Csp`

`dev.jdesk.api.Csp` — per-directive Content-Security-Policy builder for
[`Builder.contentSecurityPolicy(Csp)`](#jdeskapplicationbuilder). Start from
{@code Csp.defaults()} (the strict default) and override individual directives.

| Member | Meaning |
| --- | --- |
| `static Csp defaults()` | The framework's strict default policy as a mutable builder. |
| `static Csp empty()` | An empty policy to build from scratch. |
| `Csp directive(String name, String... sources)` | Sets a directive by name (empty sources removes it). |
| `Csp connectSrc/imgSrc/mediaSrc/scriptSrc/styleSrc/fontSrc/frameSrc/defaultSrc(String...)` | Convenience setters for common directives. |
| `String build()` | Serializes to the header value (throws `INVALID_REQUEST` if empty). |

### `AssetRoute`

`dev.jdesk.api.AssetRoute` — functional interface for an app-defined asset route under
`jdesk://app/<prefix>/...`, registered with
[`Builder.assetRoute`](#jdeskapplicationbuilder). Handlers run off the UI thread;
blocking I/O is fine. Responses stream through the asset pipeline: security headers are
added, and Range requests get 206 automatically when `contentLength` is known. `GET`/`HEAD`
read; `POST` is the non-base64 **binary upload** channel — a page
`fetch(url, {method:'POST', body})` arrives as `request.body()` (exact bytes). See
[Serving assets](../guides/serving-assets.md).

| Member | Meaning |
| --- | --- |
| `Optional<Response> serve(Request request) throws IOException` | Returns the response, or empty for a deterministic 404. `IOException` maps to a path-free 500. |
| `record Request(String path, String method, byte[] body, Map<String, String> headers)` | Path below the prefix (normalized, traversal-safe), HTTP method, raw upload bytes (empty for GET/HEAD), and request headers (lower-case keys); `header(String)` looks up case-insensitively. A `Request(String path, Map)` convenience constructor keeps GET routes source-compatible. |
| `record Response(String contentType, long contentLength, Supplier<InputStream> body, Map<String, String> headers)` | Content type, byte length (-1 unknown; Range needs it known), fresh-stream supplier, extra response headers (e.g. `Cache-Control`). |
| `static Response of(byte[] bytes, String contentType)` | Buffered response from bytes (defensive copy). |
| `static Response of(Path file, String contentType) throws IOException` | Streamed response from a file; derives the length. |
| `static Response empty()` | 200 with an empty body — the natural acknowledgement for an accepted upload. |

**Upload limits.** POST bodies are capped by the `jdesk.assets.maxUploadBytes` system
property (default 64 MiB); a larger body is rejected with **413** before the route runs.
Methods outside `GET`/`HEAD`/`POST` get **405**; a POST that matches no route is a plain
404 (the packaged asset source is read-only). Platform status: **macOS** delivers the
POST body (live-verified byte-exact, see [Serving assets](../guides/serving-assets.md));
**Windows/Linux** do not forward the request body yet (routes see an empty body).

### `FileDialog` / `FileDialogResult`

`dev.jdesk.api.FileDialog` — requests for native, app-modal open/save dialogs, used with
[`ApplicationHandle.showOpenDialog`/`showSaveDialog`](#applicationhandle). See
[Dialogs & printing](../guides/dialogs-and-printing.md).

| Member | Meaning |
| --- | --- |
| `record FileDialog.Filter(String label, List<String> extensions)` | A named type filter; extensions are lower-case, no dot. |
| `record FileDialog.OpenDialog(String title, Optional<String> directory, List<Filter> filters, boolean allowMultiple, boolean chooseDirectories)` | Open request; `OpenDialog.ofType(title, filters...)` is a shortcut. |
| `record FileDialog.SaveDialog(String title, Optional<String> directory, Optional<String> suggestedName, List<Filter> filters)` | Save request; `SaveDialog.withName(title, name, filters...)` is a shortcut. |

`dev.jdesk.api.FileDialogResult` — `record(List<String> paths)`; `isCancelled()` (empty
paths), `path()` (first path), `FileDialogResult.cancelled()`.

### `PrintJob`

`dev.jdesk.api.PrintJob` — a document file to send straight to a printer, used with
[`ApplicationHandle.printFile`](#applicationhandle).

```java
record PrintJob(String filePath, Optional<String> printerName, int copies,
    Optional<String> paperSize)
```

`PrintJob.of(filePath)` then `.toPrinter(name)` / `.withCopies(n)` /
`.withPaperSize("A4")`. `copies` must be 1..99.

## File watching

`ApplicationHandle.watchFiles(root, options, listener)` watches a directory for changes
and returns a `FileWatchHandle`. Raw OS events are coalesced within a window and delivered
as batches on one dedicated background thread (never the UI thread); a throwing listener is
logged and the watch survives. Close the handle — or let shutdown do it — to stop. Up to
128 concurrent watches.

| Type | Meaning |
| --- | --- |
| `record FileWatchEvent(Path path, Kind kind)` | One change; `Kind` is `CREATED`, `MODIFIED`, `DELETED`, or `OVERFLOW` (events dropped/coalesced — `path` is the root, rescan). |
| `record FileWatchOptions(boolean recursive, Duration coalesceWindow)` | `FileWatchOptions.RECURSIVE` / `NON_RECURSIVE` constants; `withCoalesceWindow(d)`; window `0` delivers each batch immediately. |
| `interface FileWatchHandle` | `isActive()`, `close()` (idempotent, any thread). |

**Platform status (honest).** **macOS** uses FSEvents through FFM — live-verified
event-driven at **~10–13 ms** for create/modify/delete, including Unicode paths and
recursive subtrees, on a dedicated dispatch queue (never the UI thread). Because FSEvents
reports *cumulative* per-path flags, `DELETED` is reliable (existence-checked) but the
`CREATED` vs `MODIFIED` split is best-effort — a create-with-content may surface as
`MODIFIED`; treat kinds as hints and stat the path when exact semantics matter.
**Windows/Linux** use the portable `WatchService` backend (kernel-backed and event-driven
there). The same portable backend also runs on macOS as a fallback, but the JDK degrades it
to ~2 s polling there — which is exactly why FSEvents is the macOS backend.

## Pseudo-terminals

`ApplicationHandle.openPty(spec, output)` starts a child process attached to a real PTY (a
shell, REPL, or any TTY program) and streams its output to the callback off the UI thread.
The returned `PtyHandle` is the input/control side. Up to 64 concurrent sessions; all are
closed at shutdown.

| Type | Meaning |
| --- | --- |
| `record PtySpec(List<String> command, Optional<Path> workingDirectory, Map<String,String> environment, int columns, int rows)` | `PtySpec.of(argv...)` (80×24); `withSize`, `withWorkingDirectory`, `withEnvironment`. `TERM` defaults to `xterm-256color`. |
| `interface PtyHandle` | `write(byte[])`, `resize(cols, rows)`, `isAlive()`, `exitCode()` (`OptionalInt`; 128+signal for signalled deaths), `terminate()` (SIGHUP), `kill()` (SIGKILL), `close()`. |

**Platform status (honest).** **macOS** is implemented with public `openpty` + `posix_spawnp`
(no `fork` inside the JVM) and live-verified on a real `/bin/sh`: a genuine controlling TTY
(`/dev/ttys000`), `stty size` reflecting the initial `80×24` and a mid-run `resize(100,40)`,
a propagated exit code (`exit 7` → `7`), and — because `POSIX_SPAWN_SETSID` makes the child a
session/group leader and signals target the process group — killing the session reaps a
backgrounded child too (no orphans). Reader/waiter run on platform daemon threads (they never
pin a virtual-thread carrier). **Windows (ConPTY)** and **Linux (openpty/`libutil`)** are not
implemented yet; `openPty` reports `ILLEGAL_STATE` there (PLATFORM-002).

## Secrets

### `SecretStore`

`dev.jdesk.api.SecretStore` — OS-backed secret storage (macOS Keychain, Windows DPAPI,
Linux Secret Service), namespaced per application id. Obtained from
[`ApplicationHandle.secrets()`](#applicationhandle). Calls may block on the OS
credential service — fine on command-handler virtual threads, never on the UI thread.
See [Storing secrets](../guides/storing-secrets.md).

| Member | Meaning |
| --- | --- |
| `Optional<String> get(String key)` | The stored secret, or empty when never stored. |
| `void put(String key, String value)` | Stores or replaces one secret. Keys are 1..128 chars; values up to 64 KiB. |
| `void delete(String key)` | Removes one secret; absent keys are a no-op. |

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
| `static String current()` | Exact framework build version embedded by Gradle; `development` only when the generated resource is absent. |

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
