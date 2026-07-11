# Project structure

A file-by-file tour of the project that `create-jdesk-app` generates. This is a reference:
it describes what each file is and does, so you can find your way around. It assumes the
`basic` template (the default) with package `com.example.myapp`, then contrasts the
`structured` template at the end.

Scaffold one to follow along — see [Installation](installation.md).

## The `basic` layout

```text
my-app/
├── build.gradle.kts                  # Gradle build: plugin, deps, jdesk config
├── settings.gradle.kts               # project name, repositories
├── gradlew, gradlew.bat              # Gradle wrapper (run tasks with this)
├── gradle/wrapper/                   # wrapper jar + properties
├── .gitignore
├── README.md
├── src/main/java/
│   └── com/example/myapp/
│       ├── Main.java                 # application entry point
│       └── GreetingService.java      # a command service
├── src/main/resources/
│   └── jdesk-capabilities.json       # capability grants (deny-by-default)
└── ui/
    ├── index.html                    # the page shell
    ├── package.json                  # Vite scripts (dev/build)
    ├── Build.java                    # production asset build (no Node)
    └── src/
        ├── main.js                   # the bridge client
        └── style.css                 # styles
```

### Build and project files

- **`build.gradle.kts`** — the module's Gradle build. It applies the `dev.jdesk.application`
  plugin, pins the Java 25 toolchain, declares dependencies on `dev.jdesk:jdesk-api` and
  `dev.jdesk:jdesk-runtime`, selects the per-OS platform adapter
  (`dev.jdesk:jdesk-platform-<os>`) from `os.name`, and configures the app in a `jdesk { }`
  block — `applicationId`, `mainClass`, and a `frontend { }` block pointing at `ui/`. It also
  applies Gradle's `application` plugin so `./gradlew run` launches the app, and registers
  short task aliases (`doctor`, `bindings`, `pkg`).

  The single-module templates (`basic`, `vanilla`, `react`, `vue`, `svelte`) are **classpath
  apps** — no `module-info.java`, less ceremony. The `structured` template is modular (it
  sets `mainModule` and ships a `module-info.java` per module); see the structured section
  below.

- **`settings.gradle.kts`** — sets `rootProject.name` and declares the plugin and dependency
  repositories. When you scaffold with `--jdesk-source`, this file also `includeBuild(...)`s
  your local JDesk checkout as a composite build.

- **`gradlew` / `gradlew.bat` / `gradle/wrapper/`** — the Gradle wrapper. Always invoke
  Gradle through it (`./gradlew run`) so everyone builds with the same Gradle version.

- **`.gitignore`** — ignores build output (`.gradle/`, `build/`, `ui/dist/`,
  `ui/node_modules/`, `*.log`).

- **`README.md`** — a short generated readme with the build and dev-loop commands.

### Java sources

  The single-module templates have **no `module-info.java`** — they run on the classpath, so
  you don't deal with the Java module system for a small app. (The `structured` template is
  modular and ships a `module-info.java` per module.)

  ```java
  module com.example.myapp {
      requires dev.jdesk.api;
      requires dev.jdesk.runtime;
      requires static com.fasterxml.jackson.databind;

      opens com.example.myapp to com.fasterxml.jackson.databind;
  }
  ```

- **`Main.java`** — the entry point. It builds a `JDeskApplication` with an id, the generated
  command registry (`GreetingServiceCommands.create(...)`), the capability set loaded from
  `jdesk-capabilities.json`, and one `WindowConfig`, then calls `run(args)`. It also honors
  `-Djdesk.dev`/`-Djdesk.devUrl` so `jdeskDev` can point it at the dev server.

- **`GreetingService.java`** — a **command service**: a plain class whose method is annotated
  with `@DesktopCommand("greeting.greet")` and `@RequiresCapability("greeting:use")`. The
  request and response are `public record`s. At compile time the JDesk annotation processor
  turns this class into `GreetingServiceCommands` (used in `Main`) plus TypeScript bindings —
  there is no runtime reflection scanning.

### Resources

- **`src/main/resources/jdesk-capabilities.json`** — the capability grant list. JDesk is
  deny-by-default: a command runs only if its `@RequiresCapability` value is granted to the
  window. The generated file grants `greeting:use` to the `main` window:

  ```json
  {
    "version": 1,
    "grants": [
      { "capability": "greeting:use", "windows": ["main"] }
    ]
  }
  ```

### Frontend (`ui/`)

- **`ui/index.html`** — the page shell. It holds the form (a text input and a **Greet**
  button) and a result element, and loads `src/main.js` as a module.

- **`ui/src/main.js`** — the frontend logic. It talks to Java over
  [the bridge](../architecture/ipc-protocol.md) (`window.__jdesk`): it completes the
  handshake, then on form submit invokes `greeting.greet` and renders the response. It also
  imports `style.css`.

- **`ui/src/style.css`** — the page styles.

- **`ui/package.json`** — declares the Vite dev/build scripts and Vite as a dev dependency.
  The `dev` script serves the frontend on `http://127.0.0.1:5173` for the HMR loop.

- **`ui/Build.java`** — the production asset build. It is a single-file Java program that
  copies `index.html`, `main.js`, and `style.css` into `ui/dist/` and rewrites the script
  path. The Gradle `frontend` block runs it as the `buildCommand`, so a production build (and
  `./gradlew run`) needs no Node. `ui/dist/` is what the plugin packs into the app and serves
  over `jdesk://app/`.

## How it contrasts: the `structured` template

The `structured` template targets larger apps by splitting the code into four Gradle modules
plus a shared frontend, following a domain/application/infrastructure/desktop layering:

```text
my-app/
├── settings.gradle.kts               # include("domain","application","infrastructure","desktop")
├── build.gradle.kts                  # base build, shared group/version
├── domain/                           # pure domain records (e.g. Greeting)
├── application/                      # use-case interfaces (e.g. GreetingUseCase)
├── infrastructure/                   # implementations (e.g. SystemGreetingUseCase)
├── desktop/                          # composition root: Main, command services,
│   │                                 #   jdesk-capabilities.json, the dev.jdesk.application plugin
│   └── src/main/resources/jdesk-capabilities.json
└── ui/                               # shared frontend (same files as basic)
```

The moving parts map onto the layers:

- **`domain`** — plain records with no framework dependency (`Greeting`). Exports its package;
  depends on nothing.
- **`application`** — use-case interfaces (`GreetingUseCase`) that the domain drives. Requires
  the domain module transitively.
- **`infrastructure`** — concrete implementations (`SystemGreetingUseCase implements
  GreetingUseCase`). Requires `application`.
- **`desktop`** — the composition root and the only module that applies the
  `dev.jdesk.application` plugin. It holds `Main`, a command service (`GreetingCommands`) that
  delegates to the use case, `module-info.java` requiring `dev.jdesk.api`/`dev.jdesk.runtime`
  plus the app modules, and `jdesk-capabilities.json`. Its `jdesk { }` block sets
  `mainModule` to `<id>.desktop` and lists the other modules under `development.reloadSources`
  so the dev loop rebuilds on changes across all of them.

Run the structured dev loop from the desktop module: `./gradlew :desktop:jdeskDev`. Both
templates share the same command/capability/frontend model — the `basic` layout keeps it in
one module; `structured` separates concerns for scale.

## Next steps

- [Your first app](your-first-app.md) — build, run, and modify the generated command.
