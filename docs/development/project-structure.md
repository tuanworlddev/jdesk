# Application Project Structure

How to lay out a JDesk application project. This follows [spec section 15](../../JDESK_CORE_FRAMEWORK_SPEC.md);
the concrete, runnable reference is [`examples/hello-vanilla`](../../examples/hello-vanilla).

## Default layout

```text
my-app/
├── ui/                              # frontend sources (any static web build)
├── src/main/java/dev/example/app/
│   ├── App.java                     # JDeskApplication.builder()...run(args)
│   ├── features/                    # feature-oriented command services
│   ├── domain/                      # domain model
│   └── infrastructure/              # adapters to the outside world
├── src/test/java/
├── src/main/resources/
│   ├── jdesk-capabilities.json      # deny-by-default capability grants
│   └── logback.xml                  # logging configuration
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

### Feature-oriented packages, not layered globals

Do **not** impose Spring-style global `controller`/`service`/`repository` packages.
Prefer packages organized by feature. A command service is a plain public class whose
methods are annotated with `@DesktopCommand` (see
[quick-start.md](quick-start.md)); keep each feature's service, DTOs, and domain logic
together.

### Key files

- **`jdesk-capabilities.json`** (`src/main/resources/`) — the capability grant list,
  loaded with `Capabilities.fromResource(Main.class.getModule(),
  "jdesk-capabilities.json")`. Deny-by-default:
  a command runs only if its `@RequiresCapability` value is granted to the window. Format:

  ```json
  {
    "version": 1,
    "grants": [
      { "capability": "greeting:use", "windows": ["main"] }
    ]
  }
  ```

- **`logback.xml`** (`src/main/resources/`) — logging configuration. Console output should
  clearly distinguish framework, Java-runtime, and frontend messages.

- **`ui/`** — the frontend. Its build output (`ui/dist` by default) is what the plugin
  packs into the app jar under `/web` and serves over `jdesk://app/`. Generated TypeScript
  bindings land under the frontend's `tsOutputDir` (default `<frontend>/src/generated`).
  A frontend can be omitted entirely (frontend tasks then skip with `NO-SOURCE`); a
  vanilla app can keep its assets under `src/main/resources/web` and point
  `-Djdesk.assets.dir` at them, as `hello-vanilla` does.

## Templates

The spec defines two starting templates ([section 15](../../JDESK_CORE_FRAMEWORK_SPEC.md)):

- **`basic`** — a single Java module for small applications. This is what
  [`examples/hello-vanilla`](../../examples/hello-vanilla) demonstrates: one module, one
  feature service (`GreetingService`), plain HTML/JS/CSS assets, a single
  `jdesk-capabilities.json` grant.
- **`structured`** — separate domain, application, infrastructure, desktop-composition,
  and UI modules for larger applications.

Generate either layout with the `jdesk` CLI:

```bash
jdesk create my-app --package com.example.myapp
jdesk create my-suite --template structured --package com.example.mysuite
```

The generated project includes a Gradle wrapper, JPMS descriptors, capability policy,
Vite frontend, production asset builder and the platform dependency selector. During
framework development, add `--jdesk-source /path/to/JDesk` to use a composite build.

## The `hello-vanilla` basic template, concretely

```text
examples/hello-vanilla/
├── build.gradle.kts                          # applies jdesk.java-conventions + application
├── src/main/java/dev/jdesk/examples/hello/
│   ├── Main.java                             # builder + run(args)
│   └── GreetingService.java                  # @DesktopCommand greeting.greet
└── src/main/resources/
    ├── jdesk-capabilities.json               # grants greeting:use to "main"
    └── web/                                  # index.html, main.js, style.css
```

Notable: the example depends on the framework via `project(...)` dependencies (a published
consumer would use `dev.jdesk:jdesk-api` / `-runtime` / `-codegen`), selects the platform
adapter per OS with `-PjdeskPlatform`, and serves assets from `src/main/resources/web` via
`-Djdesk.assets.dir` during `run`. See [quick-start.md](quick-start.md) for the full walk
through and [gradle-plugin-reference.md](gradle-plugin-reference.md) for the plugin-managed
build.
