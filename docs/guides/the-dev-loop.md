# Run the dev loop

Get a fast edit-refresh cycle: frontend changes hot-reload in the WebView, and Java
changes rebuild and restart the app without you touching the terminal. This guide covers
the `dev.jdesk.application` plugin's `jdeskDev` task and the `frontend { }` /
`development { }` configuration behind it.

## Check your environment first

Before the first dev run, verify your toolchain:

```bash
./gradlew jdeskDoctor
```

`jdeskDoctor` verifies the JDK toolchain (>= 25), that `jlink` and `jpackage` are present,
reports OS/arch, checks the platform WebView runtime (macOS `WebKit.framework`; Windows
WebView2 registry, optionally `-PjdeskWebView2Loader`; Linux WebKitGTK 4.1 via
`pkg-config`), and confirms your frontend tool is on `PATH` and the capabilities file is
valid. It collects *every* problem and fails once with the full remediation list — no
downloads. Fix what it reports, then continue. See the
[Gradle plugin reference](../development/gradle-plugin-reference.md) for the full checklist.

## Configure the frontend block

`jdeskDev` is driven by the `frontend { }` block in your `build.gradle.kts`. A scaffolded
project already has it; this is the shape:

```kotlin
jdesk {
    applicationId.set("com.acme.myapp")
    mainModule.set("com.acme.myapp")
    mainClass.set("com.acme.myapp.Main")

    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(listOf("npm", "run", "build"))
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))
    }
}
```

| Property | Meaning |
| --- | --- |
| `directory` | Frontend source root (`ui/`). Unset means "no frontend" and the frontend tasks skip with `NO-SOURCE`. |
| `devCommand` | The dev-server command, as an argument list. Scaffolds run `vite --host 127.0.0.1 --port 5173 --strictPort`. |
| `buildCommand` | The production build command (used by `jdeskFrontendBuild`, not the dev loop). |
| `devUrl` | The exact dev-server origin `jdeskDev` probes and injects. Must match the port your dev server binds. |
| `distDirectory` | Built assets; defaults to `directory/dist`. |

Command lists are passed as argument vectors, so paths with spaces or non-ASCII characters
are safe, and logged environments are redacted (`token`/`secret`/`password`/`key`).

## Start the loop

Install frontend dependencies once, then run `jdeskDev`:

```bash
npm install --prefix ui
./gradlew jdeskDev
```

`jdeskDev`:

1. Starts your `devCommand` (the Vite dev server).
2. Probes `devUrl` until the server answers.
3. Launches the app as a supervised Java process with `-Djdesk.dev=true` and
   `-Djdesk.devUrl=<devUrl>`, so the WebView loads from the dev server instead of the
   packaged `jdesk://app/` assets.
4. Watches your Java and resource roots and restarts the app after a successful rebuild.
5. Cleans up every child process tree on exit.

Your `Main` reads those system properties to opt into the dev server:

```java
String devUrl = System.getProperty("jdesk.devUrl");
if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
    app.devServerUrl(devUrl);
}
```

## Frontend changes: HMR, no Java restart

Edit anything under `ui/` and the dev server hot-reloads it in the WebView. The Java
process keeps running — the dev loop does not restart Java for frontend edits. This is the
fast path; keep it tight by putting UI work in `ui/`.

## Java changes: controlled restart

When you change Java sources or resources, `jdeskDev` rebuilds and swaps the process. The
`development { }` block controls this:

```kotlin
jdesk {
    development {
        javaReload.set(true)              // default: watch and restart
        reloadDebounceMillis.set(300)     // default quiet period, ms
        // reloadCommand defaults to the project's `classes` task
        // reloadSources.from(rootProject.file("another-module/src/main"))
    }
}
```

- After an edit, the plugin waits for the `reloadDebounceMillis` quiet period (default
  300 ms), then runs the rebuild command (default: the wrapper's `classes` task).
- The app is swapped **only after a successful compile**. A failed rebuild keeps the
  current process alive so it can display state while you fix the source.
- Set `javaReload` to `false` to disable Java restarts entirely.

## Watch extra modules in a structured build

In the `structured` template, the plugin runs from the `desktop` module, but your Java
lives in `domain`, `application`, and `infrastructure` too. The template adds those roots
to `reloadSources` so edits anywhere trigger a rebuild:

```kotlin
development {
    reloadSources.from(
        rootProject.file("domain/src/main"),
        rootProject.file("application/src/main"),
        rootProject.file("infrastructure/src/main")
    )
}
```

Run the dev loop from that module:

```bash
./gradlew :desktop:jdeskDev
```

## Per-OS notes

The dev loop launches your app as a named module and grants native access only to the
selected platform provider. Honor these when you run:

- **Select the platform provider.** Launching a JDesk app requires exactly one
  `PlatformProvider`. The `run` task selects it with `-PjdeskPlatform=<macos|windows|linux>`;
  zero or multiple providers fails loudly with the exactly-one-provider diagnostic — by
  design, never a silent fallback.
- **macOS needs the first thread.** AppKit must run on the process's first thread, so the
  app is launched with `-XstartOnFirstThread`. The `run` and `jdeskPackage` tasks add it
  automatically; if you launch the app another way on macOS, add it yourself.

See [prerequisites](../platform/prerequisites.md) and
[troubleshooting](../development/troubleshooting.md) when something does not launch.

## Next steps

- [Generate TypeScript bindings](generating-typescript-bindings.md) — type your commands.
- [Package your app](packaging-your-app.md) — turn the app into a distributable.
- [Gradle plugin reference](../development/gradle-plugin-reference.md) — every task and
  property.

## Short task aliases

Generated projects register short aliases so you don't memorize the long `jdesk*` task
names:

| Alias | Runs | What it does |
| --- | --- | --- |
| `./gradlew run` | (application plugin) | Build the frontend and launch the app on this OS |
| `./gradlew dev` | `jdeskDev` | Dev loop with frontend hot-reload (framework templates) |
| `./gradlew doctor` | `jdeskDoctor` | Check your JDK, WebView runtime, and configuration |
| `./gradlew bindings` | `jdeskGenerateBindings` | Regenerate the Java registry and TypeScript client |
| `./gradlew pkg` | `jdeskPackage` | Build a native application image |

The long `jdesk*` task names still work; the aliases are just shorter. The `basic` template
(no frontend dev server) registers `run`, `doctor`, `bindings`, and `pkg`; the Vite-based
templates also register `dev`.
