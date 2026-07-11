# Quick Start

This gets a real JDesk application talking between JavaScript and Java. It mirrors the
[README](../../README.md) quick start in more depth and uses only the public API. The
concrete, runnable version of everything below is
[`examples/hello-vanilla`](../../examples/hello-vanilla) — read it alongside this page.

> **Status.** JDesk is pre-alpha (see [../../IMPLEMENTATION_STATUS.md](../../IMPLEMENTATION_STATUS.md)
> and [../../VERIFICATION.md](../../VERIFICATION.md)). The framework is not yet published to
> Maven, so today apps consume it as project/`includeBuild` dependencies rather than
> `dev.jdesk:jdesk-api:<version>` coordinates. The APIs shown here are stable.

## Prerequisites

- **JDK 25** (or let the Gradle toolchain provision one).
- The platform's WebView runtime for the OS you run on — WebView2 Evergreen (Windows),
  system WebKit (macOS 13+), or WebKitGTK 4.1 (Linux). See
  [../platform/prerequisites.md](../platform/prerequisites.md).
- **Node.js is optional.** It is only needed if your chosen frontend build (Vite, etc.)
  requires it. `hello-vanilla` ships plain HTML/JS/CSS and needs no Node.

## 1. Write a command service

A "command" is a plain public method annotated with `@DesktopCommand`. Its wire name is
`dot.separated` lowerCamel segments. Every command needs a capability
(`@RequiresCapability`) unless it is explicitly marked `@PublicDesktopCommand`.

```java
package dev.example.app;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class GreetingService {
    public record GreetRequest(String name) {}
    public record GreetResponse(String message) {}

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<GreetResponse> greet(GreetRequest request, InvocationContext ctx) {
        return CompletableFuture.completedFuture(
                new GreetResponse("Hello, " + request.name() + "!"));
    }
}
```

Supported method shapes: `name(Req, InvocationContext)`, `name(InvocationContext)`, or
`name()`. `Req`/`Res` are public records (or `String`/boxed primitives; `Res` also allows
`Void`). Return type is always `CompletionStage<Res>`. The
[jdesk-codegen README](../../modules/jdesk-codegen/README.md) lists the exact type rules
and every compile-time rejection.

## 2. Wire up bindings generation

`jdesk-codegen` is an annotation processor. Add it on the `annotationProcessor` path and
it generates, in the same package as `GreetingService`, a `GreetingServiceCommands` class
plus TypeScript `types.ts`/`commands.ts`.

Today (pre-publication) apps reference the modules directly. From `hello-vanilla`'s
`build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":modules:jdesk-api"))
    implementation(project(":modules:jdesk-runtime"))
    annotationProcessor(project(":modules:jdesk-codegen"))

    // Platform adapter chosen per OS at run time (see step 5).
    val platform = providers.gradleProperty("jdeskPlatform").orNull
    if (platform != null) runtimeOnly(project(":modules:jdesk-platform-$platform"))
}
```

A published consumer would instead use
`annotationProcessor("dev.jdesk:jdesk-codegen:<version>")`. When you apply the
[Gradle plugin](gradle-plugin-reference.md), the processor is wired for you through a
`jdeskCodegen` configuration and `jdeskGenerateBindings` is the entry point.

Production packaging uses JPMS. Add `src/main/java/module-info.java`; open only the DTO
package to Jackson rather than granting reflection globally:

```java
module dev.example.app {
    requires dev.jdesk.api;
    requires dev.jdesk.runtime;
    requires static com.fasterxml.jackson.databind;

    opens dev.example.app to com.fasterxml.jackson.databind;
}
```

Set `jdesk.mainModule` to the same module name (it defaults to `applicationId`).

## 3. Declare capabilities

Deny-by-default: a command runs only if its capability is granted to the window. Put the
grants in `src/main/resources/jdesk-capabilities.json`:

```json
{
  "version": 1,
  "grants": [
    { "capability": "greeting:use", "windows": ["main"] }
  ]
}
```

Each grant lists a capability and the window ids it applies to. The capability check
happens before the payload is deserialized and before your handler runs.

## 4. Assemble and run the application

```java
package dev.example.app;

import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;

public final class Main {
    public static void main(String[] args) {
        GreetingService greetings = new GreetingService();
        JDeskApplication.Builder builder = JDeskApplication.builder()
                .id("dev.example.app")
                .commands(GreetingServiceCommands.create(greetings))     // generated
                .capabilities(Capabilities.fromResource(
                        App.class.getModule(), "jdesk-capabilities.json"))
                .window(WindowConfig.builder()
                        .id("main")
                        .title("Example")
                        .size(900, 640)
                        .entry("jdesk://app/index.html")
                        .build());

        // Optional dev-server wiring (see the HMR loop below).
        String devUrl = System.getProperty("jdesk.devUrl");
        if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
            builder.devServerUrl(devUrl);
        }
        System.exit(builder.run(args));
    }
}
```

`builder.run(args)` builds the spec, locates the single `JDeskBootstrap` provider from
`jdesk-runtime`, and runs until shutdown. Multiple services compose with
`JDeskCommands.combine(...)` or `CommandRegistry.of(...)` (see the codegen README).

## 5. Serve assets and launch

Assets are served over the custom `jdesk://app/` scheme — no HTTP server. There are two
sources:

- **Classpath** (`ClasspathAssetSource`): the default packaged form. The Gradle plugin
  packs the built frontend into the jar under `/web`, and `jdesk://app/index.html`
  resolves there.
- **Directory** (`-Djdesk.assets.dir=<dir>`): points the resolver at a folder on disk.
  `hello-vanilla`'s `run` task uses this to serve straight from
  `src/main/resources/web` during development.

Because the framework is pre-publication, the current way to launch a sample is Gradle
`run` with the OS-selected adapter:

```bash
# macOS
./gradlew :examples:hello-vanilla:run -PjdeskPlatform=macos
# Windows
./gradlew :examples:hello-vanilla:run -PjdeskPlatform=windows
# Linux
./gradlew :examples:hello-vanilla:run -PjdeskPlatform=linux
```

The sample is a named module. Its `run` task grants native access only to
`dev.jdesk.platform.<os>`, denies other illegal native access and, on macOS, adds
`-XstartOnFirstThread` (AppKit needs the process's first thread). Omitting
`-PjdeskPlatform` fails loudly with the exactly-one-provider diagnostic — that is by
design, never a fake fallback.

## 6. Call the command from the page

With `@jdesk/client` and generated bindings, the call is fully typed:

```ts
import { commands } from "./jdesk-ts/commands";
const res = await commands.greeting.greet({ name: "Tuan" });   // res: GreetResponse
```

The vanilla example instead talks the raw protocol directly over `window.__jdesk`
(nonce → `hello` → `invoke` → `result`); see
[`examples/hello-vanilla/src/main/resources/web/main.js`](../../examples/hello-vanilla/src/main/resources/web/main.js)
for a dependency-free reference and [ipc-protocol.md](../architecture/ipc-protocol.md) for
the envelope shapes.

> **CSP note.** The default Content-Security-Policy is strict and blocks inline scripts —
> this bit the framework's own smoke page during bring-up. Externalize your JS/CSS into
> files (as `hello-vanilla` does) rather than inlining them. See
> [troubleshooting.md](troubleshooting.md).

## 7. The dev / HMR loop (Gradle plugin)

When you apply `dev.jdesk.application` and configure a `frontend` block, `jdeskDev` runs
the full loop: it starts your `devCommand` (e.g. `npm run dev`), probes `devUrl` until the
dev server is up, then launches the app with `-Djdesk.dev=true -Djdesk.devUrl=<devUrl>`.
Frontend HMR works without restarting Java; the dev-server process tree is cleaned up on
exit. Java/resource changes run `classes` after a 300 ms quiet period and restart the app
only after a successful build. A compile failure leaves the current process running so it
can display state while the developer fixes the source. See
[gradle-plugin-reference.md](gradle-plugin-reference.md).

## Next steps

- [project-structure.md](project-structure.md) — how to lay out a real app.
- [gradle-plugin-reference.md](gradle-plugin-reference.md) — every plugin task.
- [../platform/prerequisites.md](../platform/prerequisites.md) — per-OS setup.
- [troubleshooting.md](troubleshooting.md) — when something does not launch.
