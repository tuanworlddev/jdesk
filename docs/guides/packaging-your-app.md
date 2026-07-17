# Package your app

Turn your JDesk project into a self-contained application image that runs without Gradle or
a globally installed JRE. This guide walks the packaging tasks in order, from the frontend
build to evidence verification. For installers and signing, continue with
[Sign and distribute your app](signing-and-distributing.md).

## Build on the target OS only

`jpackage` produces platform-native images and **cannot** cross-produce another OS's
package. Cross-packaging is forbidden: build Windows packages on Windows, macOS on macOS,
Linux on Linux ([spec 16.2](../../JDESK_CORE_FRAMEWORK_SPEC.md)). Run each command below on
the OS you are packaging for.

Verify the toolchain first — packaging needs `jlink` and `jpackage`:

```bash
./gradlew jdeskDoctor
```

## 1. Build the frontend

```bash
./gradlew jdeskFrontendBuild
```

`jdeskFrontendBuild` runs your `buildCommand` in `frontend.directory` and writes the built
assets to `distDirectory` (`ui/dist`). That directory is then packed into the application
jar under `/web` by `processResources`, so `jdesk://app/index.html` resolves inside the
packaged app. With no frontend configured, this task skips with `NO-SOURCE`.

## 2. Build the runtime image

```bash
./gradlew jdeskRuntimeImage
```

`jdeskRuntimeImage` runs `jdeps --print-module-deps --ignore-missing-deps --multi-release
<v>` over the runtime classpath to find the required JDK modules, then `jlink`s them into
`build/jdesk/runtime-image`. The image starts without a globally installed JRE and carries
**no** global native-access privilege — native access is granted per-module at the
packaging step, not baked into the image. Add extra JDK modules with `additionalModules` if
`jdeps` cannot infer a reflective dependency.

## 3. Build the application image

```bash
./gradlew jdeskPackage
```

`jdeskPackage` stages your named modules and runs
`jpackage --module-path ... --module <mainModule>/<mainClass>`, writing the app image to
`build/jdesk/package`. The launcher:

- on macOS adds `--mac-package-identifier <applicationId>` and `-XstartOnFirstThread`.

`jdeskPackage` supports two launch modes, chosen by whether `jdesk.mainModule` is set:

- **Classpath app** (the default single-module templates — no `module-info.java`): the image
  launches from `--input` jars with `--main-jar`/`--main-class`, native access is
  `--enable-native-access=ALL-UNNAMED`, and assets load from the classpath
  (`-Djdesk.assets.classpath=web`).
- **Modular app** (set `jdesk.mainModule` and ship a `module-info.java`, as the `structured`
  template does): the image launches from the module path with a tighter native-access
  boundary (`--enable-native-access=dev.jdesk.platform.<os>` and
  `--illegal-native-access=deny`).

Alongside the image, `jdeskPackage` writes two release-hygiene files next to it:

- **`checksums.sha256`** — SHA-256 for every file under the image, in GNU coreutils format,
  sorted for determinism;
- **`sbom.cyclonedx.json`** — a CycloneDX 1.7 SBOM listing the application, each
  checksummed artifact, discovered runtime libraries, and the dependency graph.

It logs `wrote checksums.sha256 (N files) and sbom.cyclonedx.json (UNSIGNED)`. The
`UNSIGNED` label is expected here — signing is applied later and only when you supply an
identity (see [Sign and distribute your app](signing-and-distributing.md)).

## 4. Run it without Gradle

The app image is a real, standalone application. Launch its packaged binary directly:

```bash
# macOS
open build/jdesk/package/*.app
# Linux
./build/jdesk/package/*/bin/<app-name>
# Windows
build\jdesk\package\<app-name>\<app-name>.exe
```

The exact launcher path depends on `jpackage`'s per-OS layout; look under
`build/jdesk/package`. No Gradle, no JDK on `PATH`, no `-PjdeskPlatform` — the correct
platform provider and native-access flags are baked into the launcher.

## 5. Smoke-test the packaged launcher

```bash
./gradlew jdeskNativeSmokeTest
```

`jdeskNativeSmokeTest` depends on `jdeskPackage`, launches the packaged image's real
launcher with `--jdesk-smoke`, and requires exit code 0 within `timeoutSeconds` (default
180 s). Your app must implement `--jdesk-smoke` as a genuine self-check that starts, probes,
and exits 0. A missing launcher, a non-zero exit, or a timeout each fail the task — this is
what proves the *packaged* app actually runs, not just the Gradle `run` task.

## 6. Verify the evidence

```bash
./gradlew jdeskVerifyEvidence
```

`jdeskVerifyEvidence` runs `dev.jdesk.testkit.evidence.VerifyMain` against
`evidenceDirectory` (default `build/evidence`): it recomputes checksums, validates schemas,
and rejects fake providers. It is an `@UntrackedTask`, so it runs every time. See
[native testing and evidence](../verification/native-testing-and-evidence.md) for the
evidence format.

## Honest status

App-image packaging (`jdeskPackage`) is implemented and verified on all three primary
targets; directly verifiable checksums and the SBOM are generated on every package.
`jdeskInstaller` builds a real installer but produces **UNSIGNED** artifacts without a
signing identity. The release workflow stages checksummed JARs and can add GitHub artifact
attestations, but OS signing/notarization still requires the publisher's credentials.
Confirm what is proven on your platform in the [verification matrix](../../VERIFICATION.md)
and the [current status](../../STATUS.md).

## Next steps

- [Sign and distribute your app](signing-and-distributing.md) — build DMG/MSI/DEB and wire
  signing hooks.
- [Packaging and signing](../packaging/packaging-and-signing.md) — the pipeline in depth.
- [Gradle plugin reference](../development/gradle-plugin-reference.md) — every task.
