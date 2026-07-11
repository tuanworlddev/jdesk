# Scaffolding (`create-jdesk-app`) and publishing

## `npx create-jdesk-app`

New JDesk apps are scaffolded the same way as Tauri / React / Next:

```bash
npm create jdesk-app@latest my-app
# or
npx create-jdesk-app@latest my-app --template react --package com.acme.myapp
```

`npx create-jdesk-app` resolves — per the npm `create-*` convention — to the npm package
**`create-jdesk-app`** (`js/create-jdesk-app`). That package is a thin, dependency-free
Node wrapper that runs the JDesk project generator (`dev.jdesk.cli`, bundled as
`jdesk-cli.jar`):

```
npx create-jdesk-app my-app --template react
        │
        ▼  (npm resolves "create-jdesk-app")
index.mjs  →  java -p jdesk-cli.jar -m dev.jdesk.cli create my-app --template react
        │
        ▼  templates + Gradle wrapper embedded in the jar
my-app/  (build.gradle.kts, settings, ui/, src/…, jdesk-capabilities.json, gradlew)
```

Node.js is used **only** to launch the scaffolder; the generated app is pure Java + a
system WebView and needs no Node at runtime (a React/Vue/Svelte template uses Node/npm
only for the dev-time frontend build). A **JDK 25+** is required to scaffold and build,
found via `JAVA_HOME` or `java` on `PATH`.

Templates: `basic`, `structured`, `vanilla`, `react`, `vue`, `svelte`. Options:
`--template`, `--package`, `--jdesk-version`, `--jdesk-source <dir>` (local composite
build for framework development), `--force`. Run with no name for an interactive prompt.

### Choosing Gradle or Maven

`create-jdesk-app` asks for a build system. **Gradle is the default and recommended** — it
has the full `dev.jdesk.application` plugin (run, dev/HMR, bindings, packaging, installer).

**Maven** is a supported alternative (`--maven` or `--build maven`) for teams standardized on
it. It generates a `pom.xml` that resolves `dev.jdesk:*` (per-OS platform via profiles), runs
the `jdesk-codegen` annotation processor (generating the Java registry and the TypeScript
client), and runs the app with `mvn exec:exec`:

```bash
npx create-jdesk-app@latest my-app --maven
cd my-app
# Pre-alpha: install the framework locally first (from a JDesk checkout):
#   ./gradlew publishToMavenLocal
(cd ui && java Build.java)   # build the UI
mvn compile                  # resolve deps, generate bindings
mvn exec:exec                # run the app
```

Maven **builds and runs** but does not package: `jlink`/`jpackage` and the doctor/dev-loop
tasks are Gradle-plugin features (ADR-002 does not include a Maven packaging plugin in v1).
For a distributable, use Gradle, or invoke `jpackage` yourself. Because Maven cannot build
against a source checkout the way Gradle's `--jdesk-source` composite build does, a Maven
project needs the `dev.jdesk:*` artifacts in a repository first — either `publishToMavenLocal`
(above) or a published release (below).

### Building the wrapper

`create-jdesk-app` bundles a freshly built jar:

```bash
./gradlew :modules:jdesk-cli:jar
npm --prefix js/create-jdesk-app run bundle   # copies the jar into the package
npm --prefix js/create-jdesk-app pack --dry-run
```

`prepublishOnly` re-bundles automatically before `npm publish`.

## Publishing the framework artifacts

Generated projects (without `--jdesk-source`) resolve `dev.jdesk:*` from a Maven
repository and the `dev.jdesk.application` plugin from the same repo. Publishing is
configured in `build-logic` (`jdesk.publishing-conventions`): every library plus the
`jdesk-bom` BOM gets POM metadata (Apache-2.0, SCM, developers), sources + javadoc jars,
and optional PGP signing.

```bash
# Local verification (no credentials):
./gradlew publishToMavenLocal

# Real publish to a repository (credentials supplied at invocation, never checked in):
./gradlew publish \
  -PjdeskPublishUrl=https://maven.pkg.github.com/tuanworlddev/jdesk \
  -PjdeskPublishUser=<user> -PjdeskPublishToken=<token>

# Signed release (spec 12.5): add an in-memory PGP key.
./gradlew publish -PsigningKey="$(cat private.asc)" -PsigningPassword=<pw> …
```

### Releasing to GitHub Packages (automated)

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds, tests, and publishes
`dev.jdesk:*` (all libraries, the BOM, and the `dev.jdesk.application` plugin) to **GitHub
Packages** using the built-in `GITHUB_TOKEN` — no external credentials. It also creates a
GitHub Release.

```bash
# cut a release
git tag v0.1.0 && git push origin v0.1.0
```

GitHub Packages requires authentication to **consume**, so add the repository and a token
(a classic PAT with `read:packages`) to the consumer:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/tuanworlddev/jdesk")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.token").orNull
            }
        }
    }
}
```

Anonymous consumption needs Maven Central, which requires a Sonatype account and a PGP
signing key (the `-PsigningKey` path above) — a follow-up once credentials are available.

The BOM lets consumers align versions:

```kotlin
dependencies {
    implementation(platform("dev.jdesk:jdesk-bom:0.1.0"))
    implementation("dev.jdesk:jdesk-api")      // version from the BOM
    implementation("dev.jdesk:jdesk-runtime")
}
```

### npm publishing

Two npm packages ship the JS side:

- **`create-jdesk-app`** — the scaffolder (bundles `jdesk-cli.jar`).
- **`@jdesk/client`** — the runtime TypeScript IPC client (`js/jdesk-client`).

```bash
npm --prefix js/create-jdesk-app publish --access public
npm --prefix js/jdesk-client publish --access public
```

## Verified end to end

Both flows were exercised on real toolchains (macOS arm64, JDK 25):

- `create-jdesk-app` scaffolds `basic`/`react` projects that compile and run codegen.
- `publishToMavenLocal` publishes all 14 artifacts (libraries, BOM, plugin marker); a
  scaffolded project in **published mode** (no `--jdesk-source`, repos = `mavenLocal` as a
  Maven Central stand-in) resolves the plugin + `dev.jdesk:*` and builds — proving the full
  publish → consume chain.

## Prerequisite for a public `npx create-jdesk-app`

The one gap before this works for outside users on `npx create-jdesk-app@latest`: the
`dev.jdesk:*` artifacts and the `dev.jdesk.application` plugin must be **published to a
public repository** (Maven Central or GitHub Packages), and the two npm packages published
to npm. The configuration above is ready; it needs the repository credentials / npm token,
which only the account owner can supply.
