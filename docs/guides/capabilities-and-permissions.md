# Grant capabilities per window

Commands are deny-by-default: a window can invoke a command only if it holds the required
capability. This guide shows you how to declare capabilities on commands and grant them to
windows in `jdesk-capabilities.json`. It assumes you can already
[define a command](defining-commands.md).

## Require a capability on the command

Declare the capability the command needs with `@RequiresCapability`. The value is a
capability name (1..128 characters, non-blank); the `area:action` convention keeps names
readable:

```java
@DesktopCommand("clipboard.read")
@RequiresCapability("clipboard:read")
public CompletionStage<String> read(InvocationContext context) {
    return CompletableFuture.completedFuture(readClipboard());
}
```

For a command that is safe to expose to any window, opt out of the capability requirement
explicitly with `@PublicDesktopCommand` instead:

```java
@DesktopCommand("app.version")
@PublicDesktopCommand
public CompletionStage<String> version() {
    return CompletableFuture.completedFuture("1.0.0");
}
```

A command with **neither** annotation is a compile-time error. The two annotations are
mutually exclusive. This is deny-by-default enforced by the annotation processor — you
cannot ship a command without making an explicit authorization decision.

## Grant capabilities in jdesk-capabilities.json

Put `jdesk-capabilities.json` on your application module's resources (next to your other
resources, so `Capabilities.fromResource` can load it). Each grant names a capability and,
optionally, the windows it applies to:

```json
{
  "version": 1,
  "grants": [
    { "capability": "greeting:use", "windows": ["main"] },
    { "capability": "clipboard:read" }
  ]
}
```

- `version` must be `1`.
- Each entry in `grants` has a `capability` string and an optional `windows` array of
  window ids.
- **Omit `windows` to grant the capability to every window.** Listing window ids restricts
  the grant to those windows.

In the example above, only the window with id `main` may invoke `greeting.greet`, but every
window may invoke `clipboard.read`. A window id here must match the `id` you gave a
[`WindowConfig`](managing-windows.md).

The file is validated strictly: an unknown field, a wrong `version`, or malformed JSON
**fails application startup** rather than silently widening or narrowing permissions. A typo
in a capability name is a real bug, so JDesk refuses to guess. For the full schema and
validation rules see the [capabilities.json reference](../reference/capabilities-json.md).

## Load the capability set

Pass the parsed set to the builder. Load it from your application module:

```java
import dev.jdesk.runtime.config.Capabilities;

JDeskApplication.builder()
    .id("com.example.app")
    .commands(GreetingServiceCommands.create(greetings))
    .capabilities(Capabilities.fromResource(
        Main.class.getModule(), "jdesk-capabilities.json"))
    .window(/* ... */)
    .run(args);
```

If you pass no `.capabilities(...)`, the set is empty — every capability-gated command is
denied.

## What happens on denial

When a window invokes a command it is not granted, the runtime returns
`CAPABILITY_DENIED`. The check runs **before payload deserialization and before your
handler** — the denied request never touches your code, and the payload is never even
parsed. On the frontend the call rejects with a `JDeskError` whose `code` is
`CAPABILITY_DENIED` and whose message is a safe, generic string with no internal detail.

This ordering is deliberate: capability evaluation is step 6 of the invoke pipeline, ahead
of deserialization (step 8) and handler execution (step 9). See the
[IPC protocol processing order](../architecture/ipc-protocol.md#processing-order-for-invoke).

## Grant the minimum

The capability gate is the enforced security boundary in JDesk, and it is per-window, not
per-frame. Any script that runs in a window — including injected or supply-chain-compromised
script — can reach the bridge and invoke every command that window is granted. Grant each
window only the capabilities it needs, and ship no dangerous capabilities by default. See
[the security model](../concepts/security-model.md) for the rationale and
[the threat model](../security/threat-model.md) for the residual risks JDesk accepts and
why.

## Related

- [Define a command](defining-commands.md) — declaring the command the capability guards.
- [Manage windows](managing-windows.md) — where window ids come from.
- [capabilities.json reference](../reference/capabilities-json.md) — the complete schema.
- [Security model](../concepts/security-model.md) and
  [threat model](../security/threat-model.md) — why the boundary is where it is.
