# create-jdesk-app

Scaffold a new [JDesk](https://github.com/tuanworlddev/jdesk) desktop application — a
**Java core + operating-system WebView**, the Tauri development model without Rust.

```bash
npm create jdesk-app@latest my-app
# or, fully guided:
npx create-jdesk-app@latest
```

Run it with no arguments and it walks you through a guided setup:

1. **Project name** — type a name (or press Enter for `my-jdesk-app`).
2. **Package name** — it suggests `com.example.<name>`; press Enter to accept, or type your
   own reverse-DNS id.
3. **Build and template** — choose Gradle or Maven, then pick a frontend with the ↑/↓
   arrow keys and Enter.

It then scaffolds the project (with a progress spinner) and prints exactly what to run next.
Any option you pass on the command line is used as-is and its prompt is skipped, so power
users and CI can go non-interactive:

```bash
npx create-jdesk-app@latest my-app --template react --package com.acme.myapp
npx create-jdesk-app@latest my-app --yes          # accept all defaults
```

Then:

```bash
cd my-app
./gradlew run          # launch the app on this OS (Windows/macOS/Linux)
./gradlew jdeskDev     # dev loop with frontend HMR (when a frontend is configured)
```

## Options

| Option | Description |
| --- | --- |
| `-b, --build <system>` | `gradle` or `maven` (default `gradle`) |
| `--maven` | Shorthand for `--build maven` |
| `-t, --template <name>` | `basic`, `structured`, `vanilla`, `react`, `vue`, `svelte`, or `solid` (default `basic`) |
| `-p, --package <id>` | Reverse-DNS Java package / application id (default `com.example.<name>`) |
| `--jdesk-version <v>` | Framework version to depend on |
| `--jdesk-source <dir>` | Use a local JDesk checkout (composite build) — for framework development |
| `-y, --yes` | Accept all defaults, no prompts (for CI or a quick start) |
| `--force` | Overwrite files in a non-empty directory |
| `-h, --help` | Show help |

The interactive prompts appear only in a terminal (TTY). When input is piped or in CI,
pass a name plus the options you need, or `--yes` for defaults.

## Requirements

- **JDK 25+** to scaffold and build (JDesk applications are built with the JDK). The
  generator finds it via `JAVA_HOME` or `java` on `PATH`.
- **Node.js** is used only to run this scaffolder. The generated application is pure Java
  and a system WebView; it does **not** require Node at runtime. A frontend build (React,
  Vue, Svelte templates) uses Node/`npm` only during development.

## How it works

This package is a thin, dependency-free wrapper around the JDesk project generator
(`dev.jdesk.cli`), bundled here as `jdesk-cli.jar`. `npx create-jdesk-app my-app` resolves
(per the npm `create-*` convention) to this package and runs
`java -p jdesk-cli.jar -m dev.jdesk.cli create my-app …`. Templates and the Gradle wrapper
are embedded in the jar, so scaffolding needs no network beyond installing the package.

Licensed under Apache-2.0.
