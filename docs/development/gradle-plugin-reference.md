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
    applicationName.set("Dragon 7")          // display name; defaults to the last applicationId segment
    mainModule.set("dev.example.app")        // defaults to applicationId
    mainClass.set("dev.example.App")

    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(listOf("npm", "run", "build"))
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))   // default: directory/dist
        // tsOutputDir: default directory/src/generated
    }

    development {
        javaReload.set(true)              // default
        reloadDebounceMillis.set(300)     // default
        // reloadCommand defaults to ./gradlew <project>:classes
        // reloadSources.from(rootProject.file("another-module/src/main"))
    }
}
```

Every property is lazy (`Property`/`DirectoryProperty`/`ListProperty`). Leaving
`frontend.directory` unset means "no frontend": the frontend tasks skip with `NO-SOURCE`.

### Extension shape

| Property | Type | Meaning |
| --- | --- | --- |
| `applicationId` | `Property<String>` | Reverse-DNS app id; validated by `jdeskDoctor`. |
| `applicationName` | `Property<String>` | Human-readable app name. Drives the packaged app's `CFBundleName` (the **bold name in the macOS menu bar**) and the runtime `Quit <Name>` item, keeping both in sync. Unset ⇒ both derive from the last `applicationId` segment. See [packaging](../packaging/packaging-and-signing.md#application-name-and-the-macos-menu-bar). |
| `mainModule` | `Property<String>` | Named JPMS application module used by production packaging; defaults to `applicationId`. |
| `mainClass` | `Property<String>` | Application entry point. |
| `frontend.directory` | `DirectoryProperty` | Frontend source root; unset ⇒ no frontend. |
| `frontend.devCommand` | `ListProperty<String>` | Dev-server command (argument list). **Unset** ⇒ `jdeskDev` runs in static frontend mode: it runs `buildCommand` on UI changes and the app reloads the page from `distDirectory` automatically (no Node required). See [the dev loop](../guides/the-dev-loop.md). |
| `frontend.buildCommand` | `ListProperty<String>` | Production build command (argument list). |
| `frontend.devUrl` | `Property<String>` | Exact dev-server origin to probe/inject. |
| `frontend.distDirectory` | `DirectoryProperty` | Built assets; default `directory/dist`. |
| `frontend.tsOutputDir` | `DirectoryProperty` | Generated TS output; default `directory/src/generated`. |
| `development.javaReload` | `Property<Boolean>` | Watch Java/resources and restart after a successful rebuild; default `true`. |
| `development.reloadCommand` | `ListProperty<String>` | Rebuild command; defaults to the project wrapper's `classes` task. |
| `development.reloadDebounceMillis` | `Property<Integer>` | Quiet period before rebuilding; default 300 ms. |
| `development.reloadSources` | `ConfigurableFileCollection` | Additional roots to watch, useful for dependent modules in structured builds. |

Command lists are passed as argument vectors, so paths with spaces and non-ASCII
characters are safe. Logged environments are redacted (`(?i)(token|secret|password|key)`).

### Non-modular dependencies

A JDesk app is a JPMS module, so a dependency that ships without a `module-info` (an *automatic
module*, e.g. LSP4J) neither resolves via `requires` nor links into the `jpackage` image. Give it a
synthesized descriptor with [`extra-java-module-info`](https://github.com/gradlex-org/extra-java-module-info)
so it works in both `run` and the packaged app — see
[Using non-modular libraries](../guides/non-modular-libraries.md).

## Tasks (group `jdesk`)

| Task | What it does | Status |
| --- | --- | --- |
| `jdeskDoctor` | Verifies JDK toolchain ≥ 25, jlink/jpackage presence, OS/arch report, and WebView runtime (macOS: `WebKit.framework`; Windows: WebView2 registry + optional `-PjdeskWebView2Loader`; Linux: WebKitGTK 4.1 through `pkg-config`), plus the frontend tool on `PATH` and extension validity. Collects **every** problem, then fails with the full remediation list. No downloads. | Implemented |
| `jdeskGenerateBindings` | Lifecycle task over `compileJava`. The annotation processor **is** the generator (ADR-005), so this depends on `compileJava` rather than running javac twice. `./gradlew jdeskGenerateBindings` is the documented entry point. Emits `<Service>Commands.java` + `types.ts`/`commands.ts`. | Implemented |
| `jdeskFrontendBuild` | Runs `buildCommand` in `frontend.directory` (argument list). Inputs: frontend sources minus `node_modules/`, `.git/`, and the dist dir; output: `distDirectory` (real up-to-date checks). Skips `NO-SOURCE` when no frontend. The built `distDirectory` is also packed into the jar under `/web` by `processResources`. | Implemented |
| `jdeskDev` | Starts and probes the frontend HMR server, launches Java as a supervised process, watches Java/resource roots, rebuilds after the configured debounce, and swaps the process only after a successful compile. Failed rebuilds keep the current app alive. All child process trees are cleaned up on exit. | Implemented + functional restart test |
| `jdeskRuntimeImage` | Runs `jdeps` over the runtime classpath, then `jlink` for the required JDK modules. Native privilege is not embedded globally in the image. | Implemented |
| `jdeskPackage` | Uses `jpackage --module-path ... --module <mainModule>/<mainClass>` and grants native access only to the selected platform module with illegal access denied. | Implemented |
| `jdeskInstaller` | Builds the OS-native installer (DMG/PKG, MSI/EXE, DEB/RPM) from the `jdeskPackage` app image via `jpackage`, on the target OS only. Type override: `-PjdeskInstallerType=<...>`. With credentials it Authenticode-signs and verifies Windows artifacts, notarizes/staples/validates macOS artifacts, or emits and verifies a detached GPG signature on Linux. | **Implemented** (real unsigned DMG/MSI/DEB verified; trusted credentials pending) |
| `jdeskNativeSmokeTest` | Depends on `jdeskPackage`; launches the packaged app-image's real launcher with `--jdesk-smoke` and requires exit 0 within `timeoutSeconds` (default 180 s). The app must implement the flag as a genuine self-check. Missing launcher / non-zero exit / timeout each fail. | Implemented |
| `jdeskVerifyEvidence` | Runs `dev.jdesk.testkit.evidence.VerifyMain` (classpath = `jdeskTestkit` configuration) against `evidenceDirectory` (default `build/evidence`): recomputes checksums, validates schemas, rejects fake providers. See [../verification/native-testing-and-evidence.md](../verification/native-testing-and-evidence.md). | Implemented |

`jdeskPackage` writes directly verifiable SHA-256 checksums and a CycloneDX 1.7 SBOM through the packager's
`ReleaseArtifacts` implementation. Installer signing remains opt-in.

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

Production applications are named modules. `jdeskPackage` grants native access only to
`dev.jdesk.platform.<os>` and adds `--illegal-native-access=deny`. A matching
`module-info.java` is therefore required. `jdeskDev` uses the same named-module/native
access boundary and patches the source set's resource output into the exploded app module.

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
