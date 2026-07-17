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
Signing itself is delegated to the OS toolchains (`signtool`, `codesign` + `notarytool`, and
`gpg`); the plugin invokes and then verifies them from the values you set. On macOS, when both
`macSigningIdentity` and `macNotarizationProfile` are set, `jdeskInstaller` code-signs the image
(Hardened Runtime + secure timestamp), then **automatically submits the built installer to Apple
notarization and staples the ticket** (`xcrun notarytool submit --wait` + `stapler staple`) so it
launches without a Gatekeeper prompt. It validates the stapled ticket before succeeding. Windows
artifacts are Authenticode-signed with an RFC 3161 timestamp and checked with `signtool verify`;
Linux installers receive an armored detached `.asc` signature which is checked with `gpg --verify`.
The command lines are built by `SigningCommands` in
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
        // For headless CI; prefer this environment variable over a literal secret.
        linuxSigningPassphrase.set(providers.environmentVariable("JDESK_GPG_PASSPHRASE"))
    }
}
```

| Property | Toolchain | Purpose |
| --- | --- | --- |
| `windowsCertificate` | `signtool` | Authenticode identity (certificate subject or thumbprint). |
| `windowsTimestampUrl` | `signtool` | RFC 3161 timestamp URL. |
| `macSigningIdentity` | `codesign` | Developer ID Application identity. |
| `macNotarizationProfile` | `notarytool` | Notarization keychain profile (`--keychain-profile`). |
| `linuxSigningKey` | `gpg` | GPG key id for an armored detached installer signature. |
| `linuxSigningPassphrase` | `gpg` stdin | Optional headless passphrase; never placed in the process arguments. |

When at least one identity is set for the current OS, the installer step signs using it;
otherwise the artifact is `UNSIGNED`. A Windows certificate also requires a timestamp URL, and a
macOS notarization profile requires a signing identity; inconsistent configuration fails before
`jpackage` runs.

## A signed pipeline needs your credentials

The signing hooks and post-sign verification are wired end to end, but a publicly trusted signed
release is **not yet demonstrated**. It requires credentials that only the publisher can supply
and that are never checked in:

- **Windows** — an Authenticode code-signing certificate.
- **macOS** — a Developer ID Application certificate plus an Apple notarization profile;
  the flow is `codesign` → `notarytool submit` → `stapler`.
- **Linux** — a GPG key for detached artifact signing; repository metadata signing is separate.

CI packages are `UNSIGNED` because these credentials are not present in CI. Do not treat any
current artifact as signed. See the [verification report](../../VERIFICATION.md) and
[packaging and signing](../packaging/packaging-and-signing.md) for exactly what is proven,
and [current status](../../STATUS.md) for the remaining distribution gates.

## Publish the framework and npm packages

Distributing your *app* is the installer above. JDesk framework releases are published only by the
tag-gated GitHub workflow after the complete platform matrix passes:

```bash
# Framework artifacts — local verification, no credentials:
./gradlew publishToMavenLocal

# Validate every synchronized version before tagging:
python3 scripts/verify_release_versions.py --tag v0.1.3

# Maintainers push the exact tag only after its commit is green:
git push origin v0.1.3
```

A Maven Central release uses an in-memory PGP key and Central Portal token from GitHub secrets.
Both npm packages authorize `release.yml` as a trusted publisher and use short-lived GitHub OIDC
credentials with provenance; there is no long-lived npm write token. See
[scaffolding and publishing](../development/scaffolding-and-publishing.md) for the full
publish → consume chain.

## Next steps

- [Package your app](packaging-your-app.md) — build the app image these installers wrap.
- [Packaging and signing](../packaging/packaging-and-signing.md) — the pipeline and SBOM
  details.
- [Scaffolding and publishing](../development/scaffolding-and-publishing.md) — framework and
  npm publishing.
