# Scaffolding and publishing

## Public quick start

`create-jdesk-app` is a dependency-free npm wrapper around the modular Java generator bundled in
`jdesk-cli.jar`:

```bash
npm create jdesk-app@latest my-app
npx create-jdesk-app@latest my-app --template react --package com.acme.myapp
```

Node launches the generator and, for Vite templates, builds the frontend. It is not present in a
packaged application. JDK 25 or newer is required to scaffold and build.

Gradle templates: `basic`, `structured`, `vanilla`, `react`, `vue`, `svelte`, and `solid`. Maven is
selected with `--maven` or `--build maven`. `--jdesk-source <checkout>` is a framework-development
override that creates a Gradle composite build; public users do not need it.

### Maven

The Maven template resolves `dev.jdesk:*` from Maven Central, configures `jdesk-codegen` as an
annotation processor and selects the current platform adapter with OS profiles:

```bash
npx create-jdesk-app@latest my-app --maven
cd my-app
(cd ui && java Build.java)
mvn compile
mvn exec:exec
```

Maven currently builds and runs. Gradle remains the complete toolchain for doctor, supervised dev,
jlink/jpackage and installer tasks; parity work is tracked in [the roadmap](../../ROADMAP.md).

## Building npm packages locally

```bash
./gradlew :modules:jdesk-cli:jar
npm ci --prefix js/create-jdesk-app
npm run bundle --prefix js/create-jdesk-app
npm test --prefix js/create-jdesk-app

npm ci --prefix js/jdesk-client
npm test --prefix js/jdesk-client
```

The generated JAR is deliberately tracked inside the scaffolder package. `prepublishOnly` refreshes
it from `modules/jdesk-cli/build/libs`; release CI builds the JAR before running npm publication.

## One synchronized release

Every public surface uses the version in `gradle.properties`:

- Maven Central: all `dev.jdesk:*` libraries, BOM, platform adapters, plugin marker and tooling;
- Gradle Plugin Portal: `dev.jdesk.application`;
- npm: `create-jdesk-app` and `jdesk-client`;
- GitHub: pre-release notes, JARs and checksums.

Before tagging, run:

```bash
python3 scripts/verify_release_versions.py
./gradlew build --stacktrace
npm ci --prefix js/jdesk-client && npm test --prefix js/jdesk-client
```

Then tag the exact commit that passed the complete native/security/package CI matrix:

```bash
git tag v0.1.3
git push origin v0.1.3
```

The tag triggers normal `ci`. Only after that exact tag SHA is green does `release.yml` publish.
The version verifier rejects drift among the tag, Gradle, Java generator, npm packages, lockfiles
and frontend templates.

Maven publications use PGP signatures and the Central Portal credentials in GitHub secrets. The
Gradle plugin uses Plugin Portal credentials. npm uses GitHub Actions trusted publishing (OIDC), so
the packages must authorize `tuanworlddev/jdesk` and workflow `release.yml` as their trusted
publisher; no long-lived npm write token is stored. npm provenance is emitted automatically.

## Public-consumer proof

Repository builds can accidentally succeed through project dependencies, composite builds, caches,
or `mavenLocal`. `.github/workflows/public-canary.yml` is intentionally separate: it waits for npm,
Maven Central and the Gradle Plugin Portal, then uses only public coordinates to scaffold and build:

- a Gradle basic application with the generator's default version;
- a Gradle React application including its production frontend build;
- a Maven application including code generation.

Run it after publishing:

```bash
gh workflow run public-canary.yml -f version=0.1.3
```

The scheduled weekly run checks the current npm `latest` version and detects registry drift after a
release.

## BOM consumption

```kotlin
dependencies {
    implementation(platform("dev.jdesk:jdesk-bom:0.1.3"))
    implementation("dev.jdesk:jdesk-api")
    implementation("dev.jdesk:jdesk-runtime")
}
```

See [STATUS.md](../../STATUS.md) for the public registry state and
[VERIFICATION.md](../../VERIFICATION.md) for current evidence.
