# Define a command

Commands are how the web frontend calls Java. This guide shows you how to declare a
`@DesktopCommand` method, wire its generated registry into your application, and call it
from JavaScript. It assumes you have a working app from
[Your first app](../getting-started/your-first-app.md).

## Declare the command

Add a `@DesktopCommand` method to a service class. Give it a wire name and an explicit
capability with `@RequiresCapability`:

```java
package com.example.app;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GreetingService {

    public record GreetRequest(String name) {
    }

    public record GreetResponse(String message) {
    }

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<GreetResponse> greet(GreetRequest request, InvocationContext context) {
        String name = request.name() == null || request.name().isBlank()
                ? "world"
                : request.name().strip();
        return CompletableFuture.completedFuture(new GreetResponse("Hello, " + name + "!"));
    }
}
```

The service class must be **public** and **top-level**. The method must be a **public
instance** method. The wire name (`"greeting.greet"`) is 1..128 characters of
dot-separated lowerCamel segments — `area.name`.

Every command requires a capability decision. Either declare `@RequiresCapability("name")`,
or opt out explicitly with `@PublicDesktopCommand` for a command safe to expose to any
window:

```java
@DesktopCommand("app.version")
@PublicDesktopCommand
public CompletionStage<String> version(InvocationContext context) {
    return CompletableFuture.completedFuture("1.0.0");
}
```

A command with neither annotation is a compile-time error — commands are deny-by-default.
See [Grant capabilities per window](capabilities-and-permissions.md) to grant
`greeting:use` to a window.

## Supported method shapes

A command method always returns `CompletionStage<Res>`. It takes one of three parameter
lists:

| Parameters | Use when |
| --- | --- |
| `(Request, InvocationContext)` | the command has a request payload |
| `(InvocationContext)` | the command has no payload but needs the context |
| `()` | the command needs neither |

```java
@DesktopCommand("clock.now")
@PublicDesktopCommand
public CompletionStage<String> now() {
    return CompletableFuture.completedFuture(java.time.Instant.now().toString());
}
```

### Request and response types

The request and response are the data contract. The annotation processor validates them
and generates matching TypeScript.

- **Request** (`Request`): a public record, `String`, or a boxed primitive
  (`Integer`, `Boolean`, …).
- **Response** (`Res`): a public record, `String`, a boxed primitive, or `Void`.

Records may nest other public records and use `List<X>`, `Map<String, X>`, and
`Optional<X>`. Wrap collections in a record rather than returning them directly. For the
full type mapping (including the Java-to-TypeScript rules) see
[Generate TypeScript bindings](generating-typescript-bindings.md) and the
[Java API reference](../reference/java-api.md).

### Run work on virtual threads

Every command handler runs on a **virtual thread**, never on the UI thread, with a default
timeout of 30 seconds. Blocking I/O inside a handler is fine — it parks the virtual thread,
not a platform thread. To return a result asynchronously, complete the `CompletionStage`
from wherever the work finishes:

```java
@DesktopCommand("report.build")
@RequiresCapability("report:build")
public CompletionStage<ReportResponse> build(ReportRequest request, InvocationContext context) {
    return CompletableFuture.supplyAsync(() -> renderReport(request));
}
```

See [Threading](../concepts/threading-and-the-event-loop.md) for the virtual-thread model and cancellation.

## Wire the generated registry into your app

The `jdesk-codegen` annotation processor turns each service into a `<Service>Commands`
class at compile time — for `GreetingService` it generates `GreetingServiceCommands` with a
static `create(...)` that returns a `CommandRegistry`. Pass that registry to
`JDeskApplication.builder().commands(...)`:

```java
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;

public final class Main {
    public static void main(String[] args) {
        GreetingService greetings = new GreetingService();
        int exit = JDeskApplication.builder()
                .id("com.example.app")
                .commands(GreetingServiceCommands.create(greetings))
                .capabilities(Capabilities.fromResource(
                        Main.class.getModule(), "jdesk-capabilities.json"))
                .window(WindowConfig.builder()
                        .id("main")
                        .title("Example")
                        .size(960, 680)
                        .entry("jdesk://app/index.html")
                        .build())
                .run(args);
        System.exit(exit);
    }
}
```

When a package declares more than one service, the processor also generates a
`JDeskCommands.combine(...)` aggregator in that package so you can pass a single registry.
Duplicate wire names across the whole compilation are rejected at compile time.

## Call the command from JavaScript

The processor emits a typed client (`commands.ts`) that wraps each command. Import it and
call the method:

```ts
import { commands } from "./jdesk-ts/commands";

const response = await commands.greeting.greet({ name: "Tuan" });
console.log(response.message); // "Hello, Tuan!"
```

If you are not using the generated bindings, call the untyped `invoke` from `@jdesk/client`
directly:

```ts
import { invoke } from "@jdesk/client";

const response = await invoke("greeting.greet", { name: "Tuan" });
```

Either way, the call travels over [the bridge](../architecture/ipc-protocol.md) as a
versioned JSON envelope. A failed call rejects with a `JDeskError` carrying a public
[error code](../reference/java-api.md) such as `CAPABILITY_DENIED` or `TIMEOUT`.

## What the annotation processor rejects

These are caught at compile time, not at runtime, so a bad contract never ships:

- A command with neither `@RequiresCapability` nor `@PublicDesktopCommand`, or with both.
- A method that is not public, is `static`, or is declared in a non-public or non-top-level
  class.
- Overloaded `@DesktopCommand` methods (the same method name declared more than once).
- A return type that is not `CompletionStage<Res>` with a concrete `Res`.
- A request or response type outside the supported set (raw `Object`, `Class`, arrays,
  `Throwable`, `MemorySegment`, generic or recursive records, non-`String` map keys).
- A duplicate wire name, or a name that is also a namespace prefix of another command.

See the [Java API reference](../reference/java-api.md) for the complete list.

## Related

- [Emit events to the frontend](emitting-events.md) — push data Java → JS.
- [Grant capabilities per window](capabilities-and-permissions.md) — authorize the command.
- [Generate TypeScript bindings](generating-typescript-bindings.md) — the typed client.
- [IPC protocol](../architecture/ipc-protocol.md) — the wire envelope and processing order.
