# Sign and distribute your app

Build an OS-native installer from your packaged app image and understand what signing
requires. This guide continues from [Package your app](packaging-your-app.md): you should
already have a working `jdeskPackage` app image before building an installer.

## Build a native installer

```bash
./gradlew jdeskInstaller
```

`jdeskInstaller` runs `jpackage` on the `jdeskPackage` app image to build the OS's native
installer into `build/jdesk/installer`. It runs **on the target OS only** — the same
cross-packaging rule as `jdeskPackage`.

The installer type defaults to the OS default; override it with `-PjdeskInstallerType`:

| OS | Formats | Example |
| --- | --- | --- |
| macOS | `dmg`, `pkg` | `./gradlew jdeskInstaller -PjdeskInstallerType=pkg` |
| Windows | `msi`, `exe` | `./gradlew jdeskInstaller -PjdeskInstallerType=msi` |
| Linux | `deb`, `rpm` | `./gradlew jdeskInstaller -PjdeskInstallerType=deb` |

The macOS DMG path is verified end-to-end locally (a real 34 MB DMG); Windows MSI and Linux
DEB are built in the CI package jobs. See
[packaging and signing](../packaging/packaging-and-signing.md) for the current
per-platform status.

## Installers are UNSIGNED without an identity

`jdeskInstaller` produces a **real but UNSIGNED** installer unless a signing identity is
configured for the current OS. Unsigned packages are fine for development and internal
verification, but they are labeled `UNSIGNED` and **do not satisfy a signed-release gate**.
Signing itself is delegated to the OS toolchains (`signtool`, `codesign` + `notarytool`,
`dpkg-sig`/`rpmsign`); the plugin only invokes them from the values you set. On macOS, when both
`macSigningIdentity` and `macNotarizationProfile` are set, `jdeskInstaller` code-signs the image
(Hardened Runtime + secure timestamp), then **automatically submits the built installer to Apple
notarization and staples the ticket** (`xcrun notarytool submit --wait` + `stapler staple`) so it
launches without a Gatekeeper prompt. The command lines are built by `SigningCommands` in
`jdesk-packager` (unit-tested independently of any certificate).

## Configure the signing hooks

The signing surface is the `jdesk { signing { } }` block. Set the identities for the
platforms you distribute:

```kotlin
jdesk {
    signing {
        // Windows — Authenticode
        windowsCertificate.set("<certificate subject or thumbprint>")
        windowsTimestampUrl.set("http://timestamp.example/rfc3161")

        // macOS — Developer ID + notarization
        macSigningIdentity.set("Developer ID Application: Acme Inc (TEAMID)")
        macNotarizationProfile.set("<notarytool keychain profile name>")

        // Linux — package signing
        linuxSigningKey.set("<GPG key id>")
    }
}
```

| Property | Toolchain | Purpose |
| --- | --- | --- |
| `windowsCertificate` | `signtool` | Authenticode identity (certificate subject or thumbprint). |
| `windowsTimestampUrl` | `signtool` | RFC 3161 timestamp URL. |
| `macSigningIdentity` | `codesign` | Developer ID Application identity. |
| `macNotarizationProfile` | `notarytool` | Notarization keychain profile (`--keychain-profile`). |
| `linuxSigningKey` | `dpkg-sig` / `rpmsign` | GPG package-signing key id. |

When at least one identity is set for the current OS, the installer step signs using it;
otherwise the artifact is `UNSIGNED`.

## A signed pipeline needs your credentials

The signing hooks are a **configuration surface**, not a turnkey signed pipeline. A fully
wired, signed-and-notarized end-to-end release is Phase-7 work and is **not yet
demonstrated**. It also requires credentials that only you can supply and that are never
checked in:

- **Windows** — an Authenticode code-signing certificate.
- **macOS** — a Developer ID Application certificate plus an Apple notarization profile;
  the flow is `codesign` → `notarytool submit` → `stapler`.
- **Linux** — a GPG key for package/repository signing.

CI packages are `UNSIGNED` because these credentials are not present in CI. Do not treat any
current artifact as signed. See the [verification report](../../VERIFICATION.md) and
[packaging and signing](../packaging/packaging-and-signing.md) for exactly what is proven,
and [implementation status](../../IMPLEMENTATION_STATUS.md) for the Phase-7 gate list.

## Publish the framework and npm packages

Distributing your *app* is the installer above. If you are also publishing JDesk itself or
its JS packages, the flows are:

```bash
# Framework artifacts — local verification, no credentials:
./gradlew publishToMavenLocal

# Real publish (credentials supplied at invocation, never checked in):
./gradlew publish \
  -PjdeskPublishUrl=https://maven.pkg.github.com/tuanworlddev/jdesk \
  -PjdeskPublishUser=<user> -PjdeskPublishToken=<token>

# The two npm packages:
npm --prefix js/create-jdesk-app publish --access public   # the scaffolder
npm --prefix js/jdesk-client publish --access public       # jdesk-client runtime
```

A signed Maven release adds an in-memory PGP key (`-PsigningKey=... -PsigningPassword=...`).
Publishing to a public repository requires repository credentials and an npm token that
only the account owner can supply. See
[scaffolding and publishing](../development/scaffolding-and-publishing.md) for the full
publish → consume chain.

## Next steps

- [Package your app](packaging-your-app.md) — build the app image these installers wrap.
- [Packaging and signing](../packaging/packaging-and-signing.md) — the pipeline and SBOM
  details.
- [Scaffolding and publishing](../development/scaffolding-and-publishing.md) — framework and
  npm publishing.
