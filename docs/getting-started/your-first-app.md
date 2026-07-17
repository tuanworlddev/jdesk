# Your first app

In this tutorial you scaffold a JDesk app, run it, and trace one round trip end to end: a
web page sends a name to a Java **command**, Java returns a typed record, and the page
renders the reply. Then you change the Java response and see it update. By the end you will
have run the full edit-and-reload loop yourself.

This is a lesson, not a reference — follow the steps in order and you will end with a working
app. For the "why" behind each piece, follow the links to Concepts and Reference.

## Before you begin

Complete [Installation](installation.md) first: you need **JDK 25+** and your OS's system
WebView. Verify the JDK:

```bash
java -version
```

JDesk is pre-alpha, but the artifacts used by this tutorial are public on Maven Central. You do
not need a framework checkout or repository credentials.

## 1. Scaffold the project

Create the app from the default `basic` template:

```bash
npx create-jdesk-app@latest my-first-app \
  --package com.example.firstapp
```

Move into it:

```bash
cd my-first-app
```

You now have a single-module project. The parts you will touch live here:

```text
my-first-app/
├── src/main/java/com/example/firstapp/
│   ├── Main.java                     # builds and runs the app
│   └── GreetingService.java          # the greeting.greet command
├── src/main/resources/
│   └── jdesk-capabilities.json       # grants greeting:use to the main window
└── ui/
    ├── index.html                    # the form
    └── src/main.js                   # calls greeting.greet over the bridge
```

For a full file-by-file tour, see [Project structure](project-structure.md).

## 2. Run it

```bash
./gradlew run
```

On Windows use `.\gradlew.bat run`. The first build takes a moment. A native window titled
**my-first-app** opens with a text input (pre-filled with `JDesk`) and a **Greet** button.
Click **Greet**. The page shows:

```text
Hello, JDesk!
```

That reply came from Java. The next steps show how.

## 3. Read the command

Open `src/main/java/com/example/firstapp/GreetingService.java`:

```java
package com.example.firstapp;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GreetingService {
    public record Request(String name) {
    }

    public record Response(String message) {
    }

    @DesktopCommand("greeting.greet")
    @RequiresCapability("greeting:use")
    public CompletionStage<Response> greet(Request request, InvocationContext context) {
        String name = request.name() == null || request.name().isBlank()
                ? "world" : request.name().strip();
        return CompletableFuture.completedFuture(new Response("Hello, " + name + "!"));
    }
}
```

This is a **command**. Three things make it one:

- `@DesktopCommand("greeting.greet")` sets its wire name — what the frontend calls.
- `@RequiresCapability("greeting:use")` says the caller must hold the `greeting:use`
  capability.
- The `Request` and `Response` `record`s are the typed payloads. The frontend sends JSON
  shaped like `Request`; Java replies with `Response`.

At compile time JDesk's annotation processor discovers this method and generates
`GreetingServiceCommands`, which `Main.java` registers with
`GreetingServiceCommands.create(service)`. There is no runtime reflection scanning. To learn
the exact rules for command methods, see
[Defining commands](../guides/defining-commands.md).

## 4. See the capability grant

The command requires `greeting:use`, and JDesk is deny-by-default: without a grant the call
is rejected before your code runs. Open
`src/main/resources/jdesk-capabilities.json`:

```json
{
  "version": 1,
  "grants": [
    { "capability": "greeting:use", "windows": ["main"] }
  ]
}
```

This grants `greeting:use` to the window whose id is `main` — the window `Main.java` creates.
That is why clicking **Greet** works: the calling window holds the capability the command
requires.

## 5. Trace the frontend call

Open `ui/src/main.js`. The page talks to Java over **the bridge** — the injected
`window.__jdesk` channel. The exchange has four message kinds:

```js
document.addEventListener("jdesk-message", (event) => {
  const message = JSON.parse(event.detail);
  if (message.kind === "nonce") {
    nonce = message.nonce;
    window.__jdesk.post(JSON.stringify({
      v: 1, kind: "hello", client: "my-first-app", clientVersion: "0.1.0", nonce
    }));
  } else if (message.kind === "helloAck") {
    result.textContent = message.ok ? "Connected" : message.error.message;
  } else if (message.kind === "result") {
    const handler = pending.get(message.id);
    pending.delete(message.id);
    handler(message);
  }
});

document.querySelector("#greet-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const id = `request-${++nextId}`;
  pending.set(id, (message) => {
    result.textContent = message.ok ? message.value.message : message.error.message;
  });
  window.__jdesk.post(JSON.stringify({
    v: 1, kind: "invoke", id, command: "greeting.greet", nonce,
    payload: { name: document.querySelector("#name").value }
  }));
});
```

Read it as a sequence:

1. **nonce** — on each navigation the runtime injects a fresh session nonce. The page stores
   it and must echo it in every message.
2. **hello** — the page sends a `hello` envelope with the nonce; the runtime replies
   `helloAck`. This handshake is required before any command.
3. **invoke** — on submit, the page posts an `invoke` envelope naming the command
   (`greeting.greet`), a unique `id`, the nonce, and the JSON `payload` (`{ name }`).
4. **result** — Java runs `greet(...)` on a virtual thread and returns exactly one `result`
   envelope with the same `id`. On success it carries `value` (your `Response`); the page
   renders `message.value.message`.

The success envelope looks like this on the wire:

```json
{"v":1, "kind":"result", "id":"request-1", "ok":true, "value":{"message":"Hello, JDesk!"}}
```

The exact envelope shapes, error codes, and processing order are in
[the IPC protocol reference](../architecture/ipc-protocol.md); the design behind it is in
[How IPC works](../concepts/how-ipc-works.md).

## 6. Change the response and re-run

Now close the loop yourself. Edit `GreetingService.java` and change the response message:

```java
return CompletableFuture.completedFuture(
        new Response("Hey " + name + ", welcome to JDesk!"));
```

Save, then run again:

```bash
./gradlew run
```

Click **Greet**. The page now shows:

```text
Hey JDesk, welcome to JDesk!
```

You changed Java, rebuilt, and saw the new reply render in the WebView — the core JDesk
development loop. (For frontend-only changes, `./gradlew jdeskDev` reloads the page without
restarting Java; install the frontend dependencies first with `npm install --prefix ui`.)

## Next steps

- [Defining commands](../guides/defining-commands.md) — the full rules for command methods,
  payloads, and return types.
- [Emitting events](../guides/emitting-events.md) — push data from Java to the page instead
  of only replying to calls.
- [How IPC works](../concepts/how-ipc-works.md) — why the bridge, nonce, and capability
  checks work the way they do.
