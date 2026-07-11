# JDesk documentation

JDesk is a framework for building desktop applications with a **Java 25 core and a web
frontend rendered by the operating system's own WebView** — the Tauri development model,
without Rust. You write your application and plugin logic in Java, build your UI with any
web stack (React, Vue, Svelte, or plain HTML), and ship a small native app that uses
WebView2 on Windows, WKWebView on macOS, and WebKitGTK on Linux. No bundled Chromium, no
local HTTP server, no Node.js at runtime.

These docs are organized so you can learn JDesk from scratch and then look things up as you
build. If you are new, read **Getting Started** top to bottom. If you know the basics, jump
to a **Guide** for the task at hand, a **Concept** to understand how something works, or the
**Reference** for exact details.

> **Status.** JDesk is pre-alpha. See the [status](../IMPLEMENTATION_STATUS.md) and the
> [verification matrix](../VERIFICATION.md) for exactly what is implemented and verified on
> which platforms. The docs mark anything unfinished.

## Getting started

Start here. These pages are sequential and end with a working app.

1. [Introduction](getting-started/introduction.md) — what JDesk is, why it exists, and how
   it compares to Tauri and Electron.
2. [Installation](getting-started/installation.md) — prerequisites and
   `npx create-jdesk-app`.
3. [Project structure](getting-started/project-structure.md) — what the generated project
   contains and how the pieces fit.
4. [Your first app](getting-started/your-first-app.md) — a hands-on tutorial: build a
   greeting app that calls Java from the web UI and back.

## Guides

Task-oriented how-tos for when you are building.

- [Define a command](guides/defining-commands.md)
- [Emit events to the frontend](guides/emitting-events.md)
- [Stream binary data to the frontend](guides/streaming-binary-data.md)
- [Build a networked / real-time app](guides/networked-and-realtime-apps.md)
- [Store secrets in the OS credential store](guides/storing-secrets.md)
- [Use native dialogs and printing](guides/dialogs-and-printing.md)
- [Automate and E2E-test your app](guides/automation-and-e2e.md)
- [Grant capabilities and permissions](guides/capabilities-and-permissions.md)
- [Manage windows and the app lifecycle](guides/managing-windows.md)
- [Serve assets over `jdesk://app/`](guides/serving-assets.md)
- [Choose a frontend framework](guides/choosing-a-frontend.md)
- [Use the dev loop and HMR](guides/the-dev-loop.md)
- [Generate the TypeScript client](guides/generating-typescript-bindings.md)
- [Package your app](guides/packaging-your-app.md)
- [Sign and distribute](guides/signing-and-distributing.md)

## Concepts

Understand how JDesk works and why it is designed this way.

- [Architecture overview](architecture/overview.md)
- [How IPC works](concepts/how-ipc-works.md)
- [Threading and the event loop](concepts/threading-and-the-event-loop.md)
- [Native memory and FFM](concepts/native-memory-and-ffm.md)
- [The security model](concepts/security-model.md)

## Reference

Exact, complete descriptions to look up while working.

- [Java API](reference/java-api.md)
- [`create-jdesk-app` and the `jdesk` CLI](reference/cli.md)
- [Gradle plugin](development/gradle-plugin-reference.md)
- [IPC protocol](architecture/ipc-protocol.md)
- [`jdesk-capabilities.json`](reference/capabilities-json.md)
- [Error codes](reference/error-codes.md)
- [`jdesk-client` (TypeScript)](../js/jdesk-client/README.md)

## Going deeper

- [Platform prerequisites and limitations](platform/prerequisites.md)
- [Native testing and evidence](verification/native-testing-and-evidence.md)
- [Packaging and signing](packaging/packaging-and-signing.md)
- [Scaffolding and publishing](development/scaffolding-and-publishing.md)
- [Threat model and capability guide](security/threat-model.md)
- [Contributing](development/contributing.md)

## Architecture Decision Records

The reasoning behind each design choice:
[001 Java 25 / JPMS / FFM](architecture/ADR-001-java25-jpms-ffm.md) ·
[002 Gradle-first](architecture/ADR-002-gradle-first.md) ·
[003 System WebViews](architecture/ADR-003-system-webviews.md) ·
[004 No production localhost](architecture/ADR-004-no-localhost-production.md) ·
[005 Compile-time registration](architecture/ADR-005-compile-time-registration.md) ·
[006 Async message passing](architecture/ADR-006-async-message-passing.md) ·
[007 JVM distribution first](architecture/ADR-007-jvm-distribution-first.md) ·
[008 RSS baselines](architecture/ADR-008-rss-baselines.md) ·
[009 Linux WebKitGTK evolution](architecture/ADR-009-linux-webkitgtk-evolution.md)

---

Writing docs? Follow the [style guide](STYLE_GUIDE.md).
