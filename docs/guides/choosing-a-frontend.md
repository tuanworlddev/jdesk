# Choose a frontend template

Pick the frontend stack for a new JDesk app and understand what each template gives you.
Every template ships the same Java core and the same bridge contract; they differ only in
how the `ui/` folder is written and built. This guide assumes you have scaffolded before
(see [Scaffolding](../development/scaffolding-and-publishing.md)).

## Pick a template when you scaffold

`create-jdesk-app` selects the frontend through the `--template` flag or the interactive
menu:

```bash
npx create-jdesk-app@latest my-app --template react --package com.acme.myapp
```

Run it with no project name for the menu:

```bash
npx create-jdesk-app@latest
```

```
Select a template:
  1) Basic           — single Gradle module, plain HTML/JS frontend (great for learning)
  2) Vanilla + Vite  — single module, Vite + vanilla TypeScript
  3) React + Vite    — single module, Vite + React
  4) Vue + Vite      — single module, Vite + Vue
  5) Svelte + Vite   — single module, Vite + Svelte
  6) Structured      — multi-module: domain / application / infrastructure / desktop
```

The valid `--template` values are `basic`, `vanilla`, `react`, `vue`, `svelte`, `solid`, and
`structured` (default `basic`). An unknown name fails before any files are written.

## What each template gives you

| Template | Frontend | Dev server | Production build | Project shape |
| --- | --- | --- | --- | --- |
| `basic` | Plain HTML/JS, no framework | Vite | `Build.java` (copies files) | Single Gradle module |
| `vanilla` | Vite + vanilla JS/TS | Vite | `vite build` | Single module |
| `react` | Vite + React 19 | Vite | `vite build` | Single module |
| `vue` | Vite + Vue 3 | Vite | `vite build` | Single module |
| `svelte` | Vite + Svelte 5 | Vite | `vite build` | Single module |
| `structured` | Plain HTML/JS, no framework | Vite | `Build.java` (copies files) | Four modules: `domain`, `application`, `infrastructure`, `desktop` |

Two axes decide the choice:

- **Frontend stack.** `basic` and `structured` ship a dependency-free `ui/` — plain
  `index.html`, `src/main.js`, `src/style.css`. `vanilla`, `react`, `vue`, `svelte`, and `solid`
  add the corresponding framework dependencies and let Vite bundle them.
- **Project shape.** `structured` is the only multi-module template. It splits Java across
  `domain`, `application`, `infrastructure`, and `desktop` modules and runs the plugin from
  the `desktop` module. Use it when you want a layered architecture; use any of the others
  for a single application module.

`structured` uses the same plain-HTML frontend as `basic`, so pick it for its *Java*
layout, not its UI stack. If you want a framework and a layered backend, start from
`structured` and add a framework to `ui/` yourself.

## The bridge contract every frontend uses

Whatever template you choose, the frontend talks to Java over the same injected channel —
[the bridge](../architecture/ipc-protocol.md), reachable as `window.__jdesk`. The contract
is identical across all templates:

1. On each navigation, the platform layer injects a fresh nonce as a control envelope
   delivered on the document:

   ```js
   document.addEventListener("jdesk-message", ({ detail }) => {
     const message = JSON.parse(detail);   // detail is a JSON string
     // { "v": 1, "kind": "nonce", "nonce": "<hex>" }
   });
   ```

2. The frontend echoes the nonce back in a `hello` and waits for `helloAck`:

   ```js
   window.__jdesk.post(JSON.stringify({
     v: 1, kind: "hello", client: "my-app", clientVersion: "0.1.0", nonce
   }));
   ```

3. After the handshake, the frontend sends `invoke` envelopes and correlates each `result`
   by `id`:

   ```js
   window.__jdesk.post(JSON.stringify({
     v: 1, kind: "invoke", id: "request-1", command: "greeting.greet", nonce,
     payload: { name: "JDesk" }
   }));
   ```

Outgoing messages always go through `window.__jdesk.post(json)`; incoming messages always
arrive as `jdesk-message` `CustomEvent`s whose `detail` is the JSON envelope string. The
generated `src/main.js` in each template is a working reference for this flow. See
[the IPC protocol](../architecture/ipc-protocol.md) for every envelope shape and the
processing order.

## How `ui/` builds and where `dist` goes

The Gradle plugin's `frontend { }` block wires the same three commands in every template:

| Property | Value |
| --- | --- |
| `devCommand` | `npm run dev` → `vite --host 127.0.0.1 --port 5173 --strictPort` |
| `devUrl` | `http://127.0.0.1:5173` |
| `distDirectory` | `ui/dist` |

The `buildCommand` is where the templates diverge:

- **`basic` and `structured`** run a single-file Java program with the JDK's source
  launcher:

  ```kotlin
  buildCommand.set(javaLauncher.map {
      listOf(it.executablePath.asFile.absolutePath, "Build.java")
  })
  ```

  `ui/Build.java` creates `dist/` and copies `index.html`, `src/main.js`, and
  `src/style.css` into it (rewriting the `/src/main.js` script path to `./main.js`). There
  is no bundler in the production build — the plain files are copied verbatim.

- **`vanilla`, `react`, `vue`, `svelte`, `solid`** run Vite:

  ```kotlin
  buildCommand.set(listOf("npm", "run", "build"))   // vite build
  ```

Either way, `jdeskFrontendBuild` runs the `buildCommand` in `ui/` and writes the result to
`distDirectory` (`ui/dist`). That built directory is then packed into the application jar
under `/web` by `processResources`, so `jdesk://app/index.html` resolves to your built
assets in the packaged app. See [The dev loop](the-dev-loop.md) and the
[Gradle plugin reference](../development/gradle-plugin-reference.md).

## When to use the generated TypeScript client

Every template's `src/main.js` speaks the raw bridge protocol directly. That keeps the
templates dependency-free, but you write the nonce/hello/invoke plumbing by hand. For
anything beyond a first command, switch to the generated typed client:

- **Raw bridge** — good for learning the protocol and for the smallest possible frontend.
  You build envelopes and parse `jdesk-message` events yourself.
- **Generated client** — `jdesk-codegen` emits `types.ts` and `commands.ts` at compile
  time, and [`jdesk-client`](../../js/jdesk-client/README.md) provides the `invoke`
  runtime they call. You then write:

  ```ts
  import { commands } from "./generated/commands";

  const response = await commands.greeting.greet({ name: "Tuan" });
  ```

  The handshake, request ids, timeouts, and cancellation are handled for you, and the call
  is fully typed against your Java records.

See [Generate TypeScript bindings](generating-typescript-bindings.md) for how to wire the
generated client into any template.

## Next steps

- [The dev loop](the-dev-loop.md) — run `jdeskDev` and get frontend HMR.
- [Generate TypeScript bindings](generating-typescript-bindings.md) — type the bridge.
- [Package your app](packaging-your-app.md) — build a distributable.
