# CLI reference

JDesk ships two command-line tools that scaffold and build applications:

- **`create-jdesk-app`** — the Node/npx scaffolder (`npm create jdesk-app`). A thin,
  dependency-free wrapper around the Java generator.
- **`jdesk`** — the Java CLI (`dev.jdesk.cli.JDeskCli`) that generates projects and drives
  Gradle builds.

Both require a **JDK 25+** to scaffold and build. Node.js is used only to run the
scaffolder; generated applications are pure Java plus a system WebView and do not require
Node at runtime.

## `create-jdesk-app`

### Synopsis

```bash
npm create jdesk-app@latest <project-name> [options]
npx create-jdesk-app@latest <project-name> [options]
```

Run with no project name in a TTY to enter the [interactive prompt](#interactive-prompt).
Run with no project name when stdin is not a TTY to print usage and exit with code `2`.

### Options

| Option | Meaning | Default |
| --- | --- | --- |
| `-t, --template <name>` | Template to scaffold: `basic`, `structured`, `vanilla`, `react`, `vue`, `svelte`, or `solid`. | `basic` |
| `-p, --package <id>` | Reverse-DNS Java package / application id. | `com.example.<name>` |
| `--jdesk-version <v>` | Framework version to depend on. | generator default |
| `--jdesk-source <dir>` | Use a local JDesk checkout (composite build) — for framework development. Resolved to an absolute path. | — |
| `--force` | Overwrite files in a non-empty directory. | off |
| `-h, --help` | Print usage and exit. | — |

An unknown option (any other `-`-prefixed argument) fails with exit code `1`. Only one
project name may be given.

### Template choices

`create-jdesk-app` offers these templates (the interactive menu lists them in this
order):

| Id | Label | Contents |
| --- | --- | --- |
| `basic` | Basic | single Gradle module, plain HTML/JS frontend |
| `vanilla` | Vanilla + Vite | single module, Vite + vanilla TypeScript |
| `react` | React + Vite | single module, Vite + React |
| `vue` | Vue + Vite | single module, Vite + Vue |
| `svelte` | Svelte + Vite | single module, Vite + Svelte |
| `structured` | Structured | multi-module: domain / application / infrastructure / desktop |

The scaffolder validates `--template` against `basic`, `structured`, `vanilla`, `react`,
`vue`, `svelte`, `solid`; an unknown value fails with exit code `1`. (The underlying `jdesk` CLI
additionally accepts `maven`; `create-jdesk-app` does not expose it.)

### Interactive prompt

When no project name is supplied and stdin is a TTY, the scaffolder prompts for:

1. **Project name** — re-prompts until non-empty and free of `/` and `\`.
2. **Template** — a numbered menu (`1`–`6`, default `1` = `basic`); a template name may
   also be typed directly.
3. **Java package** — default `com.example.<slug>` where `<slug>` is the lowercased name
   with non-alphanumerics removed (prefixed `app` if empty or leading-digit).

Flags passed on the command line take precedence over prompt answers.

### JDK requirement and behavior

- The scaffolder locates a JDK ≥ 25 via `JAVA_HOME` (`bin/java` / `bin\java.exe`), then
  `java` on `PATH`. A JDK older than 25 fails with a message pointing at
  `JAVA_HOME`; no JDK 25+ found fails likewise. Both exit with code `1`.
- It then runs `java -p jdesk-cli.jar -m dev.jdesk.cli/dev.jdesk.cli.JDeskCli create
  <name> …`, forwarding `--template`, `--package`, `--jdesk-version`, `--jdesk-source`,
  and `--force`.
- The process exits with the Java CLI's exit code. On success it prints the `cd` /
  `./gradlew run` / `./gradlew jdeskDev` next steps.

## `jdesk` (Java CLI)

`dev.jdesk.cli.JDeskCli`. Invoked as a module, e.g.:

```bash
java -p jdesk-cli.jar -m dev.jdesk.cli/dev.jdesk.cli.JDeskCli <command> [options]
```

Or through an installed distribution (`./gradlew :modules:jdesk-cli:installDist`) as
`jdesk <command>`.

### Commands

| Command | Meaning |
| --- | --- |
| `create <directory>` | Generate a JDesk application into `<directory>`. |
| `build` | Build and test the current application (runs `./gradlew build`). Accepts no arguments. |
| `bundle` | Build the native installer for this OS (runs `./gradlew jdeskInstaller`). Accepts no arguments. |
| `--help`, `-h`, `help`, or no arguments | Print usage and exit `0`. |

`build` and `bundle` locate the Gradle wrapper (`gradlew` / `gradlew.bat`) in the current
directory and fail if none is present; they must be run from inside a generated project.
On Windows the wrapper is invoked via `cmd.exe /c`.

### `create` options

| Option | Meaning | Default |
| --- | --- | --- |
| `--template <name>` | `basic`, `structured`, `vanilla`, `react`, `vue`, `svelte`, or `maven`. | `basic` |
| `--package <java.package>` | Reverse-DNS package / application id; must match `[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)+`. | derived from name |
| `--name <display-name>` | Application and Gradle project name. | target directory's file name |
| `--jdesk-version <version>` | Framework version. Must not be blank. | `0.1.0-SNAPSHOT` |
| `--jdesk-source <directory>` | Use a local JDesk composite build. Must contain `settings.gradle.kts`. | — |
| `--force` | Overwrite generated files in a non-empty target. | off |

The single positional argument is the target directory. Providing more than one, or an
unknown `-`-prefixed option, is an error.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success. |
| `1` | I/O failure (`IOException`). |
| `2` | Usage error (`CliException`) — unknown command/option, missing target, invalid template/package, non-empty target without `--force`, and similar. |
| `130` | Interrupted while running Gradle. |

### Templates

`basic`, `structured`, `vanilla`, `react`, `vue`, `svelte`, `solid`, `maven`. An unsupported value
fails with a usage error. When the target directory exists and is non-empty, `create`
fails unless `--force` is passed.

### Default package derivation

When `--package` is omitted, the package is `com.example.<slug>`, where `<slug>` is the
project name lowercased with non-alphanumerics stripped, prefixed with `app` if it is
empty or starts with a digit.

### Token substitution

Template files are copied with these placeholder tokens replaced:

| Token | Replaced with |
| --- | --- |
| `@PROJECT_NAME@` | The application / project name. |
| `@PACKAGE@` | The Java package. |
| `@PACKAGE_PATH@` | The package with `.` replaced by `/`. |
| `@APP_ID@` | The Java package (used as application id). |
| `@JDESK_VERSION@` | The framework version. |
| `@PLUGIN_VERSION@` | ` version "<version>"` normally; empty when `--jdesk-source` is set. |
| `@SOURCE_INCLUDE@` | Empty normally; a `pluginManagement` `includeBuild(...)` line for `--jdesk-source`. |
| `@SOURCE_BUILD_INCLUDE@` | Empty normally; a settings `includeBuild(...)` line for `--jdesk-source`. |

A template file named `gitignore` is written as `.gitignore`. The Gradle wrapper
(`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
`gradle/wrapper/gradle-wrapper.properties`) is copied from the CLI distribution, and
`gradlew` is marked executable. Paths that would escape the target directory are rejected.

## See also

- [Scaffolding and publishing](../development/scaffolding-and-publishing.md) — how the
  generator and templates are built.
- [Gradle plugin reference](../development/gradle-plugin-reference.md) — the tasks
  `build`/`bundle` invoke (`build`, `jdeskInstaller`, `jdeskDev`).
- [`jdesk-capabilities.json`](capabilities-json.md) — the capability file generated into
  new projects.
