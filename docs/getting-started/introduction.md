# Introduction

JDesk is a framework for building cross-platform desktop applications. You write your
application logic in **Java 25** and your user interface as a **web frontend**, and JDesk
renders that frontend in the **operating system's own WebView** — WebView2 on Windows,
WKWebView on macOS, and WebKitGTK on Linux. The result is a small, native desktop app with
no bundled browser and no Rust.

If you know Tauri, the model will feel familiar: a compiled backend, a web UI, the system
WebView, and a type-safe bridge between them. JDesk keeps that model but replaces Rust with
the JVM, so your backend, plugins, and build are all Java and Gradle.

## The mental model

A JDesk app has three parts:

- **A Java core** — your commands, events, and application logic, running on the JVM with
  virtual threads. This is where the real work happens.
- **A web frontend** — HTML/CSS/JS built with any stack (React, Vue, Svelte, or none). It
  runs in the system WebView and talks to Java over a typed, asynchronous bridge.
- **A platform adapter** — a per-OS module that creates the native window and WebView and
  wires up the bridge using only documented OS APIs, through Java's Foreign Function &
  Memory API (FFM). You never touch it directly.

The frontend calls Java by invoking **commands**; Java pushes data to the frontend by
emitting **events**. Every command runs on a virtual thread, never on the UI thread, and
every call is authorized against a **deny-by-default capability model** before your code
runs.

```
  Web frontend  ──invoke──►  Java command  (virtual thread)
  (system WebView)  ◄─event──  Java core
        │
        └─ assets served over jdesk://app/  (no HTTP server)
```

## Why JDesk exists

**No bundled Chromium.** Electron ships a full browser with every app, costing 100+ MB per
install and a large memory footprint. JDesk uses the WebView already on the user's machine,
so apps are small.

**No Rust.** Tauri gets small binaries by compiling a Rust backend. JDesk gets them from a
trimmed JVM runtime image (`jlink`) instead, so teams that already work in Java — Spring,
Android, Gradle — can build desktop apps without learning a new systems language.

**No Node.js at runtime.** The frontend is static files served over a custom `jdesk://app/`
scheme — there is no local web server in production. Node is only a build-time tool, and
only if your chosen frontend needs it.

**Type-safe, compile-time IPC.** Commands are discovered by a Java annotation processor at
compile time, which also generates a typed TypeScript client. There is no runtime
reflection and no hand-written glue that can drift out of sync.

**Secure by default.** Every command requires an explicit capability grant. Navigation is
locked to the app origin, popups are denied, and the asset protocol rejects path traversal.
Security is enforced in Java, never trusted to the frontend.

## How it compares

| | JDesk | Tauri | Electron |
| --- | --- | --- | --- |
| Backend language | Java (JVM) | Rust | Node.js |
| Renderer | System WebView | System WebView | Bundled Chromium |
| Bundle size | Small (jlink runtime) | Smallest | Large (100+ MB) |
| Runtime dependency | Trimmed JVM (bundled) | None | Bundled Chromium |
| IPC | Compile-time typed commands/events | Typed commands/events | IPC channels |
| Best fit | Java teams, JVM ecosystem | Rust/native teams | Max web-parity, one engine everywhere |

JDesk trades Electron's guarantee of one identical rendering engine on every OS for smaller
apps that use each platform's native WebView — the same trade-off Tauri makes. If pixel- and
feature-identical rendering across every OS is a hard requirement, Electron is still the
safer choice.

## When to use JDesk

Reach for JDesk when you want a small native desktop app, your team is comfortable in Java
and Gradle, and you are happy building the UI with web technology. It fits internal tools,
developer tools, and productivity apps especially well.

Consider something else if you need mobile support (JDesk is desktop-only for v1), if you
cannot accept per-OS WebView differences, or if your app must run without any JVM at all.

## Honest status

JDesk is **pre-alpha**. The core, all three platform adapters, the command/event bridge,
capabilities, code generation, the Gradle plugin, and packaging are implemented and verified
on real system WebViews. Signed release packages and a few conveniences are still in
progress. The [verification matrix](../../VERIFICATION.md) is the source of truth for what
is proven on which platform, and these docs flag anything unfinished rather than overstating
it.

## Next steps

- [Installation](installation.md) — set up your machine and scaffold a project with
  `npx create-jdesk-app`.
- [Your first app](your-first-app.md) — build a working app in a few minutes.
- [Architecture overview](../architecture/overview.md) — how the pieces fit together.
