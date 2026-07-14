# Updating applications

The **jdesk-updater** module provides the security-critical update control plane without
coupling an application to one installer format:

- strict signed JSON manifest parsing;
- independent Ed25519 signatures for manifest metadata and package bytes;
- stable, beta and internal channels with semantic-version downgrade prevention;
- HTTPS-only bounded downloads (literal loopback HTTP is test-only);
- owner-only update and download state;
- immutable version directories and atomic activation;
- one-attempt health confirmation and automatic rollback on the next launch;
- SHA-256 verification again immediately before launch.

## Bootstrap contract

The updater must be called by a small launcher that remains outside the version being
replaced. The launcher creates an UpdateTransaction, calls prepareLaunch(), executes the
payload returned by UpdateLaunch.packagePath(), and calls confirmHealthy() only after the
child signals that startup completed.

The first launch of a newly staged version is marked pending. If it exits before the
launcher confirms health, the next prepareLaunch() selects the previous authenticated
payload and reports rolledBack=true.

JDesk intentionally does not guess how to execute package.bin. Applications may use a
portable app archive, a modular launcher payload, or hand the verified file to an OS
installer. Replacing a running jpackage image is platform-specific and must be tested by
the application. The framework responsibility ends at an authenticated immutable payload
and atomic version selection.

## Checking and staging

Create UpdatePolicy with UpdatePolicy.systemProperties(), then construct UpdateManager
with the transaction, separate manifest and package public keys, and a private download
directory. Call checkAndStage(manifestUri, currentVersion).

The HTTP client refuses redirects, compressed responses, oversized bodies, mismatched
Content-Length, credentials in package URLs, non-HTTPS transport, invalid signatures,
unknown manifest fields and unsupported schema versions. Temporary downloads are deleted
after verification and staging.

## Phased rollout

A manifest carries an optional `rolloutPercentage` (0–100, default 100 = full rollout). Pass a
stable install id when constructing `UpdateManager` — `InstallIdentity.loadOrCreate(configDir
.resolve("install-id"))` returns an anonymous, owner-only per-install UUID — and a newer release
only stages once that install's deterministic bucket is within the manifest's reach. Buckets are
per release, so raising the percentage over time only ever admits more installs and never flips one
back out. An install that is not yet in the rollout returns `UpdateResult.Status.HELD_BACK` instead
of `STAGED`; leave the install id null to disable rollout gating (everyone updates). The package is
still SHA-256- and signature-verified regardless, so the percentage only paces which installs stage
an already-trusted release.

## Generating manifests

Publish the release side with `SignedManifestWriter.write(packageFile, version, channel, packageUri,
minimumCurrentVersion, rolloutPercentage, publishedAtEpochSeconds, packageKey, manifestKey)`: it
hashes and Ed25519-signs the package, signs the manifest payload (separate keys let the package key
stay offline), and returns the JSON a CI job or hosted endpoint serves. It is the exact counterpart
of `SignedManifestVerifier`, so a written manifest always verifies under the matching public key.

## Managed properties

| Property | Default |
| --- | --- |
| jdesk.update.enabled | true |
| jdesk.update.channel | stable |
| jdesk.update.allowDowngrade | false |
| jdesk.update.maxPackageBytes | 512 MiB |
| jdesk.update.maxManifestBytes | 64 KiB |
| jdesk.update.connectTimeoutMs | 10 seconds |
| jdesk.update.requestTimeoutMs | 5 minutes |
| jdesk.update.allowInsecureLoopback | false (tests only) |

Invalid values fail closed. Proxy and custom trust-store configuration use the standard
Java HTTP client system properties; applications should validate their corporate proxy
and CA setup in a managed-device test environment.

## Manifest fields

Schema version 1 signs every field except manifestSignature: version, channel, packageUri,
exact size, SHA-256, raw-package Ed25519 signature, publication time, optional minimum
current version, and the rollout percentage. Use UpdateManifest.signingPayload() as the exact
bytes for generating or verifying the manifest signature (SignedManifestWriter does this for you).
Do not invent a JSON canonicalization scheme.
