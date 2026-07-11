# Gradle Plugin Reference

The `dev.jdesk.application` plugin ([spec section 14](../../JDESK_CORE_FRAMEWORK_SPEC.md))
drives the JDesk developer workflow: bindings, frontend build, the dev loop, runtime
images, packaging, and evidence verification. It is implemented in Java and its behavior
is covered by Gradle TestKit functional tests. This page is the reference; the
authoritative source is [`modules/jdesk-gradle-plugin/README.md`](../../modules/jdesk-gradle-plugin/README.md)
and the plugin sources.

## Applying and configuring

```kotlin
plugins {
    id("dev.jdesk.application")
}

jdesk {
    applicationId.set("dev.example.app")     // reverse-DNS, validated by jdeskDoctor
    mainClass.set("dev.example.App")

    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(listOf("npm", "run", "build"))
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))   // default: directory/dist
        // tsOutputDir: default directory/src/generated
    }
}
```

Every property is lazy (`Property`/`DirectoryProperty`/`ListProperty`). Leaving
`frontend.directory` unset means "no frontend": the frontend tasks skip with `NO-SOURCE`.

### Extension shape

| Property | Type | Meaning |
| --- | --- | --- |
| `applicationId` | `Property<String>` | Reverse-DNS app id; validated by `jdeskDoctor`. |
| `mainClass` | `Property<String>` | Application entry point. |
| `frontend.directory` | `DirectoryProperty` | Frontend source root; unset ⇒ no frontend. |
| `frontend.devCommand` | `ListProperty<String>` | Dev-server command (argument list). |
| `frontend.buildCommand` | `ListProperty<String>` | Production build command (argument list). |
| `frontend.devUrl` | `Property<String>` | Exact dev-server origin to probe/inject. |
| `frontend.distDirectory` | `DirectoryProperty` | Built assets; default `directory/dist`. |
| `frontend.tsOutputDir` | `DirectoryProperty` | Generated TS output; default `directory/src/generated`. |

Command lists are passed as argument vectors, so paths with spaces and non-ASCII
characters are safe. Logged environments are redacted (`(?i)(token|secret|password|key)`).

## Tasks (group `jdesk`)

| Task | What it does | Status |
| --- | --- | --- |
| `jdeskDoctor` | Verifies JDK toolchain ≥ 25, jlink/jpackage presence, OS/arch report, WebView runtime (macOS: `WebKit.framework`; Windows: WebView2 registry + optional `-PjdeskWebView2Loader`; other OSes report "not applicable"/deferred), the frontend tool on `PATH`, and extension validity. Collects **every** problem, then fails with the full remediation list. No downloads. | Implemented |
| `jdeskGenerateBindings` | Lifecycle task over `compileJava`. The annotation processor **is** the generator (ADR-005), so this depends on `compileJava` rather than running javac twice. `./gradlew jdeskGenerateBindings` is the documented entry point. Emits `<Service>Commands.java` + `types.ts`/`commands.ts`. | Implemented |
| `jdeskFrontendBuild` | Runs `buildCommand` in `frontend.directory` (argument list). Inputs: frontend sources minus `node_modules/`, `.git/`, and the dist dir; output: `distDirectory` (real up-to-date checks). Skips `NO-SOURCE` when no frontend. The built `distDirectory` is also packed into the jar under `/web` by `processResources`. | Implemented |
| `jdeskDev` | Starts `devCommand` as a child process, probes `devUrl` (60 × 500 ms, configurable), then launches the app via `javaexec` with `-Djdesk.dev=true -Djdesk.devUrl=<devUrl>`. The dev-server process tree is destroyed (`destroy` → `destroyForcibly` + descendants) in a `finally` block and a JVM shutdown hook; the app process dies with the build. This is the HMR/dev loop. | Implemented |
| `jdeskRuntimeImage` | Runs the toolchain's `jdeps --print-module-deps --ignore-missing-deps --multi-release <v>` over the runtime classpath, then `jlink` into `build/jdesk/runtime-image`. Extra modules via `additionalModules`. See the native-access note below. | Implemented (native-access fallback) |
| `jdeskPackage` | `jpackage --type app-image` combining the runtime image with app jars (staged into `build/jdesk/package-input`), output `build/jdesk/package`. macOS also gets `--mac-package-identifier <applicationId>` and `-XstartOnFirstThread`. | Implemented |
| `jdeskInstaller` | Registered but **fails with a clear "lands in Phase 7" error**. Installer types (MSI/DMG/DEB) are not implemented; nothing fakes success. | **Deferred (Phase 7 WIP — fails loudly)** |
| `jdeskNativeSmokeTest` | Depends on `jdeskPackage`; launches the packaged app-image's real launcher with `--jdesk-smoke` and requires exit 0 within `timeoutSeconds` (default 180 s). The app must implement the flag as a genuine self-check. Missing launcher / non-zero exit / timeout each fail. | Implemented |
| `jdeskVerifyEvidence` | Runs `dev.jdesk.testkit.evidence.VerifyMain` (classpath = `jdeskTestkit` configuration) against `evidenceDirectory` (default `build/evidence`): recomputes checksums, validates schemas, rejects fake providers. See [../verification/native-testing-and-evidence.md](../verification/native-testing-and-evidence.md). | Implemented |

There is **no `jdeskRelease`/SBOM task wired into the plugin yet.** The
`ReleaseArtifacts` helper (SHA-256 checksums + CycloneDX 1.5 SBOM) exists and is
unit-tested in `jdesk-packager`, but it is not yet invoked by a plugin task — see
[../packaging/packaging-and-signing.md](../packaging/packaging-and-signing.md). Planned for
Phase 7.

## How `jdeskGenerateBindings` rides on `compileJava`

The `jdesk-codegen` annotation processor emits both the Java registry and the TypeScript
in one javac pass (ADR-005). The plugin therefore:

- creates a `jdeskCodegen` configuration (default dependency
  `dev.jdesk:jdesk-codegen:<plugin version>`) and makes `annotationProcessor` extend it;
- adds a `CommandLineArgumentProvider` on `compileJava` contributing
  `-Ajdesk.ts.outputDir=<frontend.tsOutputDir>` and declares that directory as a
  `compileJava` output (correct TS incrementality);
- makes `jdeskGenerateBindings` a lifecycle task depending on `compileJava`.

Builds that cannot resolve the published artifact (isolated TestKit consumers, composite
builds before publication) override the default:
`dependencies { jdeskCodegen(project(":modules:jdesk-codegen")) }` or
`jdeskCodegen(files(...))`. The same pattern applies to `jdeskTestkit`.

## Native access tradeoff (be aware)

v1 applications launch from the classpath (the unnamed module), so `jdeskRuntimeImage`
embeds `--add-options=--enable-native-access=ALL-UNNAMED`. That grants native access to
**all** classpath code — broader than the per-module grant a fully modular application
would use (`--enable-native-access=dev.jdesk.platform.<os>`). `jdeskRuntimeImage` prints a
warning about this fallback every time. Named-module native-access images are **deferred
to Phase 7 packaging** ([../../IMPLEMENTATION_STATUS.md](../../IMPLEMENTATION_STATUS.md)).

## Configuration cache

`jdeskDoctor`, `jdeskFrontendBuild`, `jdeskGenerateBindings`, `jdeskRuntimeImage`,
`jdeskPackage`, and `jdeskVerifyEvidence` are configuration-cache compatible (asserted by
a TestKit test). `jdeskDoctor`, `jdeskDev`, `jdeskNativeSmokeTest`, and
`jdeskVerifyEvidence` are `@UntrackedTask` (they must run every time) — orthogonal to
configuration-cache compatibility.

## Known test-coverage limitation (honest)

`jdeskPackage`/`jdeskNativeSmokeTest` run real `jpackage`/launcher processes and are
exercised end-to-end only by the packaging phase (Phase 7) gates, not by the plugin
module's own TestKit suite — a full jpackage run per unit test is too slow and needs a
windowed app implementing `--jdesk-smoke`. jlink/jpackage argument construction is
unit-tested in `jdesk-packager`.

See also: [quick-start.md](quick-start.md),
[../packaging/packaging-and-signing.md](../packaging/packaging-and-signing.md),
[../verification/native-testing-and-evidence.md](../verification/native-testing-and-evidence.md).
