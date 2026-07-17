# Installation

Set up your machine for JDesk and scaffold a new project with `create-jdesk-app`. This
page covers the prerequisites, the scaffolder and its options, and how to run the generated
app on each operating system. It is the setup step before
[Your first app](your-first-app.md).

If you have not read [Introduction](introduction.md) yet, start there for the mental model.

## Prerequisites

### JDK 25+ (required)

JDesk apps are built and run with the JDK — you need **JDK 25 or newer** on your machine to
scaffold, build, and run. The scaffolder looks for a JDK in this order:

1. `JAVA_HOME`, if set (`$JAVA_HOME/bin/java`).
2. `java` on your `PATH`.

If it finds an older Java, it stops and asks you to point `JAVA_HOME` at a JDK 25; if it
finds none, it asks you to install one (for example from [Adoptium](https://adoptium.net))
and put `java` on `PATH` or set `JAVA_HOME`. Verify your toolchain first:

```bash
java -version
```

The output must report version `25` or higher. Set `JAVA_HOME` if you keep multiple JDKs:

```bash
# macOS / Linux
export JAVA_HOME=/path/to/jdk-25
```

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "C:\path\to\jdk-25"
```

### A system WebView for your OS (required to run)

JDesk renders your frontend in the operating system's own WebView — there is no bundled
browser. Each OS needs its WebView runtime present. The details, including versions and the
`jdeskDoctor` check, are in [Platform prerequisites](../platform/prerequisites.md); the
short version:

| OS | WebView runtime | Notes |
| --- | --- | --- |
| Windows | Microsoft Edge WebView2 (Evergreen) | Ships/updates on modern Windows 10 1809+; install it if absent. |
| macOS | System WebKit (WKWebView) | Part of macOS 13 (Ventura) or newer; nothing to install. |
| Linux | WebKitGTK 4.1 | Install the system package: `sudo apt-get install libwebkit2gtk-4.1-0` (Ubuntu 22.04+ or equivalent). |

### Node.js (optional)

Node.js is **not** required to run a JDesk app — the generated application is pure Java and
a system WebView. You need Node only for frontend tooling:

- The `react`, `vue`, `svelte`, and `vanilla` templates build their frontend with **Vite**,
  which needs Node.
- The `basic` template's production build uses a plain Java build script (no Node), so
  `./gradlew run` works without Node. Its optional hot-reload dev loop (`./gradlew jdeskDev`)
  runs Vite, which does need Node.

Install Node only if you pick a Vite-based template or want the HMR dev loop. See the
[per-OS notes](../platform/prerequisites.md#common-all-platforms).

## Scaffold a project

Create a new project with `create-jdesk-app`. Both the `npm create` and `npx` forms resolve
to the same package:

```bash
npm create jdesk-app@latest my-app
```

```bash
npx create-jdesk-app@latest my-app
```

Run it with no project name for an interactive prompt that asks for a name, a template, and
a Java package:

```bash
npm create jdesk-app@latest
```

### The template menu

The interactive prompt lists these templates. The default is **Basic** (option 1):

| # | Template | Id | What you get |
| --- | --- | --- | --- |
| 1 | Basic | `basic` | Single Gradle module, plain HTML/JS frontend. Great for learning. |
| 2 | Vanilla + Vite | `vanilla` | Single module, Vite + vanilla TypeScript. |
| 3 | React + Vite | `react` | Single module, Vite + React. |
| 4 | Vue + Vite | `vue` | Single module, Vite + Vue. |
| 5 | Svelte + Vite | `svelte` | Single module, Vite + Svelte. |
| 6 | Structured | `structured` | Multi-module: `domain` / `application` / `infrastructure` / `desktop`. |

Pick one non-interactively with `--template`:

```bash
npx create-jdesk-app@latest my-app --template react --package com.acme.myapp
```

### Options

| Option | Description |
| --- | --- |
| `-t, --template <name>` | `basic`, `vanilla`, `react`, `vue`, `svelte`, or `structured` (default `basic`). |
| `-p, --package <id>` | Reverse-DNS Java package / application id (default `com.example.<name>`). |
| `--jdesk-version <v>` | Framework version to depend on. |
| `--jdesk-source <dir>` | Use a local JDesk checkout as a composite build — see below. |
| `--force` | Overwrite files in a non-empty directory. |
| `-h, --help` | Show usage and exit. |

## Consuming the framework

JDesk is **pre-alpha**, but its Java artifacts are public on Maven Central. A normally
scaffolded Gradle or Maven project resolves them anonymously; no GitHub token, local Maven
publication, or framework checkout is required.

Framework contributors may pass `--jdesk-source /path/to/JDesk` to use a local Gradle
[composite build](https://docs.gradle.org/current/userguide/composite_builds.html). This is a
development override, not part of the normal quick start. The exact publish/consume chain is in
[Scaffolding and publishing](../development/scaffolding-and-publishing.md). See the current
[status](../../STATUS.md) and [verification evidence](../../VERIFICATION.md) for release caveats.

## Run the app

Move into the project and launch it. The generated `build.gradle.kts` auto-detects your OS
and selects the matching platform adapter, so no extra flag is needed:

```bash
cd my-app
./gradlew run
```

On Windows, use the batch wrapper:

```powershell
cd my-app
.\gradlew.bat run
```

The `run` task builds the frontend, packs the assets, and opens a native window. On macOS it
adds `-XstartOnFirstThread` automatically (AppKit requires the process's first thread). A
native window titled after your project appears, with a text input and a **Greet** button
wired to a Java command.

To develop the frontend with hot reload, install the frontend dependencies once and start
the dev loop:

```bash
npm install --prefix ui
./gradlew jdeskDev
```

`jdeskDev` starts the Vite dev server and launches the app against it, so frontend edits
reload without restarting Java. (For the `structured` template the dev task is
`./gradlew :desktop:jdeskDev`.)

## Next steps

- [Project structure](project-structure.md) — a tour of every file the scaffolder generates.
- [Your first app](your-first-app.md) — build and modify a working command end to end.
- [Platform prerequisites](../platform/prerequisites.md) — full per-OS requirements and
  limitations.
