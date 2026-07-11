# Generate TypeScript bindings

Turn your `@DesktopCommand` methods into a typed TypeScript client so the frontend calls
Java through `commands.area.name(req)` instead of hand-built envelopes. Bindings are
generated at compile time by the `jdesk-codegen` annotation processor — there is no runtime
reflection and no separate generator to run out of band. This guide assumes you already
have commands; see the [codegen README](../../modules/jdesk-codegen/README.md) and
[ADR-005](../architecture/ADR-005-compile-time-registration.md) for the design.

## How generation is wired

The annotation processor **is** the generator (ADR-005): it emits the Java command registry
and the TypeScript client in the same `javac` pass. With the `dev.jdesk.application` plugin
applied, this is already wired:

- the plugin creates a `jdeskCodegen` configuration (default dependency
  `dev.jdesk:jdesk-codegen:<plugin version>`) and makes `annotationProcessor` extend it;
- it adds `-Ajdesk.ts.outputDir=<frontend.tsOutputDir>` to `compileJava` and declares that
  directory as a compile output, so the generated TypeScript participates in incremental
  builds.

`frontend.tsOutputDir` defaults to `directory/src/generated` — for the standard `ui/`
layout that is `ui/src/generated/`. Override it in the `frontend { }` block if you keep
generated code elsewhere:

```kotlin
jdesk {
    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        tsOutputDir.set(layout.projectDirectory.dir("ui/src/generated"))
    }
}
```

Builds that cannot resolve the published `jdesk-codegen` artifact (isolated consumers,
composite builds before publication) point the configuration at a local build instead:

```kotlin
dependencies {
    jdeskCodegen(project(":modules:jdesk-codegen"))   // or jdeskCodegen(files(...))
}
```

## Generate the bindings

The documented entry point is a lifecycle task over `compileJava`:

```bash
./gradlew jdeskGenerateBindings
```

Because the processor runs inside `compileJava`, any task that compiles Java (`classes`,
`jdeskDev`, `jdeskFrontendBuild`'s upstream, packaging) regenerates the bindings too. You
rarely call `jdeskGenerateBindings` directly except to force generation without a full
build.

## What gets generated

For a service like the scaffolded `GreetingService`:

```java
public final class GreetingService {
    public record Request(String name) {}
    public record Response(String message) {}

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<Response> greet(Request request, InvocationContext context) { ... }
}
```

the processor emits three kinds of output.

### `<Service>Commands.java` — the Java registry

`GreetingServiceCommands` is a final class in the same package with
`public static CommandRegistry create(GreetingService instance)`, holding one
`CommandDefinition` per command, sorted by wire name. Your `Main` composes it:

```java
JDeskApplication.builder()
    .commands(GreetingServiceCommands.create(new GreetingService()))
    // ...
```

When a package declares more than one service class, the processor also emits
`JDeskCommands` with `combine(CommandRegistry...)` so you can compose registries:

```java
CommandRegistry registry = JDeskCommands.combine(
        GreetingServiceCommands.create(greetings),
        FileServiceCommands.create(files));
```

### `types.ts` — the DTO interfaces

One interface per DTO record, sorted by name, mapping Java types to TypeScript:

| Java | TypeScript |
| --- | --- |
| `String`, `char` | `string` |
| numeric primitives / boxes | `number` |
| `boolean` | `boolean` |
| `List<X>` | `X[]` |
| `Map<String, X>` | `Record<string, X>` |
| `Optional<X>` | `X \| null` |
| record | interface |
| `Void` response | `void` |

### `commands.ts` — the typed client

A nested `commands` object built from the dot-separated wire names, each wired to `invoke`
imported from `@jdesk/client`:

```ts
import { commands } from "./generated/commands";

const response = await commands.greeting.greet({ name: "Tuan" });
// response is typed as GreetingServiceResponse: { message: string }
```

## Use it from the frontend

The generated `commands.ts` imports `invoke` from [`@jdesk/client`](../../js/jdesk-client/README.md),
so add that runtime to your frontend:

```bash
npm install --prefix ui @jdesk/client
```

`@jdesk/client` performs the nonce/`hello` handshake lazily on the first `invoke`, assigns
unique request ids, enforces the 1 MiB client-side size limit, and supports per-call
timeouts and `AbortSignal` cancellation. You call commands and `await` results:

```ts
import { commands } from "./generated/commands";
import { JDeskError } from "@jdesk/client";

try {
  const { message } = await commands.greeting.greet({ name: "Tuan" });
  document.querySelector("#result").textContent = message;
} catch (error) {
  if (error instanceof JDeskError) {
    // error.code is a public ErrorCode name, e.g. CAPABILITY_DENIED
  }
}
```

This replaces the raw `window.__jdesk.post(...)` plumbing that the templates ship with. See
[Choose a frontend template](choosing-a-frontend.md) for when to prefer the generated
client over the raw bridge.

## Determinism

Identical input produces byte-identical output: no timestamps, no absolute paths, fixed
`\n` newlines, commands sorted by wire name, record components in declaration order, and
interfaces and imports sorted by name. Every generated file starts with
`// Generated by jdesk-codegen. Do not edit.` Commit the generated files or regenerate them
in CI — either way the output is stable, so a regeneration produces no spurious diff.

## Compile-time rejections

The processor validates commands as it generates and **fails the build** rather than
emitting broken bindings. It rejects:

- duplicate or grammar-violating command names, and name/namespace conflicts (`a.b` vs
  `a.b.c`);
- missing or conflicting capability annotations;
- non-public methods, classes, or DTOs, and non-record DTOs;
- unsupported types — `Object`, `Class`, `Method`, `Throwable`, `MemorySegment`,
  wildcards, raw generics, arrays, generic records, recursive records;
- overloaded command methods;
- return types other than `CompletionStage<Res>`;
- DTO simple-name collisions across packages (the TypeScript interface name is the record's
  simple name).

Fix the Java and recompile; a green build guarantees a consistent registry and client.

## Next steps

- [Choose a frontend template](choosing-a-frontend.md) — raw bridge vs generated client.
- [The dev loop](the-dev-loop.md) — bindings regenerate on every compile.
- [IPC protocol](../architecture/ipc-protocol.md) — what the client speaks underneath.
