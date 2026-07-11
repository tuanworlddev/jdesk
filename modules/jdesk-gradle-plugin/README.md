# jdesk-gradle-plugin

Plugin ID: `dev.jdesk.application` (spec section 14). Implementation is Java;
functional tests run real isolated consumer builds through Gradle TestKit.

## Extension

```kotlin
jdesk {
    applicationId.set("dev.example.app")   // reverse-DNS, validated by jdeskDoctor
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

All properties are lazy (`Property`/`DirectoryProperty`/`ListProperty`). Leaving
`frontend.directory` unset means "no frontend": frontend tasks skip with NO-SOURCE.

## Tasks (group `jdesk`)

| Task | What it really does |
| --- | --- |
| `jdeskDoctor` | Verifies JDK toolchain >= 25, jlink/jpackage presence, OS/arch report, WebView runtime (macOS: `WebKit.framework`; Windows: WebView2 registry + optional `-PjdeskWebView2Loader`; other OSes report "not applicable"/deferred), frontend tool on `PATH`, extension validity. Collects **every** problem, then fails with the full remediation list. No downloads. |
| `jdeskGenerateBindings` | Lifecycle task over `compileJava` (see design below). |
| `jdeskFrontendBuild` | Runs `buildCommand` in `frontend.directory` via an argument list (spaces/non-ASCII safe). Inputs: frontend sources minus `node_modules/`, `.git/` and the dist directory; output: `distDirectory` (real up-to-date checks). Logged environments are redacted (`(?i)(token|secret|password|key)`). |
| `jdeskDev` | Starts `devCommand` as a child process, probes `devUrl` (60 x 500 ms, configurable), then launches the app via `javaexec` with `-Djdesk.dev=true -Djdesk.devUrl=<devUrl>`. The dev-server process tree is destroyed (`destroy` → `destroyForcibly` + descendants) in a `finally` block and a JVM shutdown hook; the app process is Gradle-managed and dies with the build. |
| `jdeskRuntimeImage` | Runs the toolchain's `jdeps --print-module-deps --ignore-missing-deps --multi-release <v>` over the runtime classpath, then `jlink` with the resulting module set into `build/jdesk/runtime-image`. Embeds `--add-options=--enable-native-access=ALL-UNNAMED` (see tradeoff below). Extra modules via `additionalModules`. |
| `jdeskPackage` | `jpackage --type app-image` combining the runtime image with the app jars (staged into `build/jdesk/package-input`), output `build/jdesk/package`. macOS also gets `--mac-package-identifier <applicationId>` and `-XstartOnFirstThread`. |
| `jdeskInstaller` | Registered but **fails with a clear "lands in Phase 7" error**. Installer types (MSI/DMG/DEB) are not implemented; nothing fakes success. |
| `jdeskNativeSmokeTest` | Depends on `jdeskPackage`; launches the packaged app-image's real launcher with `--jdesk-smoke` and requires exit code 0 within `timeoutSeconds` (default 180 s). The app must implement the flag as a genuine self-check (start, probe, exit 0). No launcher / non-zero exit / timeout each fail. |
| `jdeskVerifyEvidence` | Runs `dev.jdesk.testkit.evidence.VerifyMain` (classpath = `jdeskTestkit` configuration) against `evidenceDirectory` (default `build/evidence`). |

## Design: `jdeskGenerateBindings` rides on `compileJava`

The jdesk-codegen annotation processor **is** the binding generator (ADR-005): it emits
`<Service>Commands` Java registries and `types.ts`/`commands.ts` in one javac pass.
Running javac twice would either duplicate work or drift. Therefore:

- the plugin creates a `jdeskCodegen` configuration (default dependency
  `dev.jdesk:jdesk-codegen:<plugin version>`) and makes `annotationProcessor` extend it;
- a `CommandLineArgumentProvider` on `compileJava` contributes
  `-Ajdesk.ts.outputDir=<frontend.tsOutputDir>` and declares that directory as a
  `compileJava` output (correct incrementality for the generated TypeScript);
- `jdeskGenerateBindings` is a lifecycle task depending on `compileJava`, so
  `./gradlew jdeskGenerateBindings` is the documented entry point regardless of how the
  generation is wired internally.

Builds that cannot resolve the published artifact (e.g. isolated TestKit consumers, or
composite builds before publication) override the default:
`dependencies { jdeskCodegen(files(...)) }` or `jdeskCodegen(project(":modules:jdesk-codegen"))`.
The same pattern applies to `jdeskTestkit`.

The built frontend (`distDirectory`) is additionally packed into the jar under `/web`
by `processResources`, so packaged classpath apps ship their assets.

## Native access tradeoff

v1 applications launch from the classpath, i.e. the unnamed module, so the runtime
image embeds `--enable-native-access=ALL-UNNAMED`. That grants native access to *all*
classpath code — broader than the per-module grant a fully modular application would
use (`--enable-native-access=dev.jdesk.platform.<os>`). `jdeskRuntimeImage` prints a
warning about this fallback every time; named-module images arrive with Phase 7
packaging.

## Configuration cache

Doctor, frontendBuild, generateBindings, runtimeImage, package and verifyEvidence are
configuration-cache compatible (covered by a TestKit test asserting
"Reusing configuration cache"). `jdeskDoctor`, `jdeskDev`, `jdeskNativeSmokeTest` and
`jdeskVerifyEvidence` are `@UntrackedTask` (they must run every time), which is
orthogonal to configuration-cache compatibility.

## Testing

`./gradlew :modules:jdesk-gradle-plugin:test` — unit tests plus TestKit functional
tests that generate consumer builds in temp directories (including one with spaces and
non-ASCII characters in the path), apply the plugin via `withPluginClasspath()`, and
execute real tasks: doctor success/failure modes, frontend build + up-to-date behavior,
NO-SOURCE skip, configuration-cache reuse, real jdesk-codegen TypeScript generation and
a real `jdeps`+`jlink` runtime image. jlink/jpackage argument construction is
unit-tested in `modules/jdesk-packager`.

Known limitation (honest): `jdeskPackage`/`jdeskNativeSmokeTest` execute real
`jpackage`/launcher processes and are exercised end-to-end only by the packaging phase
(Phase 7) gates, not by this module's test suite — a full jpackage run per test is too
slow and needs a windowed app that implements `--jdesk-smoke`.
