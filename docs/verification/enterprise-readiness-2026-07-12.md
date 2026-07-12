# Enterprise readiness hardening — 2026-07-12

Commit tested locally: `c27b9b8e63a79be067e14a45e46bb5563f2644f1`

## Implemented

- Public API baseline gate and build-derived `JDeskVersion.current()`.
- Ed25519-authenticated update manifests and packages, strict SemVer/channel/downgrade
  policy, bounded HTTPS downloads, atomic activation, health confirmation, and rollback.
- Centrally managed restrictions for DevTools, automation, console forwarding, and
  external-browser access. Policy can only remove application permissions.
- Explicit, bounded support bundles with secret/path redaction and owner-only file access.
- CycloneDX 1.7 SBOM inventory, dependency graph, package checksums, Dependabot, CodeQL,
  dependency review, and optional GitHub artifact attestations.
- Installer extraction checks on Windows, Linux, and macOS CI lanes.
- Enforced startup, IPC p95/p99, and RSS performance budgets.
- Generated basic and structured applications implement the packaged native smoke contract.

## Verification performed

| Gate | Result |
| --- | --- |
| Clean Gradle check | PASS — 82 tasks rerun without build cache |
| Current local XML reports | PASS — 844 tests, 0 failures/errors/skips |
| TypeScript client | PASS — 3 tests; npm audit reported 0 vulnerabilities |
| Generated consumer app | PASS — CLI create, jlink, jpackage, native `.app` launch, exit 0 |
| Package integrity | PASS — all 122 generated SHA-256 entries verified |
| SBOM | PASS — CycloneDX 1.7 parsed; 132 components and dependency graph |
| Native stress | PASS — evidence `1783851900-c0b1eb7b955c39e1`, verifier green |
| Performance | startup 251 ms; 10,000 IPC/0 mismatch; p95 7 ms; p99 9 ms; RSS 293,568,512 bytes |
| Native installer | PASS — DMG created, mounted, launcher executable present |

The DMG SHA-256 was
`7ce379acc7b345eea028abe13bdd3b71aa5a160e1e8c42cbe5e890c9cd3b8606`.
It is intentionally ad-hoc/unsigned (`TeamIdentifier=not set`).

## Remaining release work

These items are not represented as completed:

1. Supply real Windows/macOS/Linux signing identities and macOS notarization credentials;
   publish and verify signed installers from clean release runners.
2. Run the new commit on the complete Windows/Linux/macOS CI matrix and CodeQL. The push
   is currently rejected by GitHub until the `tuanworlddev` account verifies its email.
3. Integrate `UpdateTransaction.prepareLaunch()` into each product's external bootstrap.
   The framework authenticates, stages, selects, and rolls back `package.bin`; the product
   still defines how its jpackage payload is installed or executed outside the running JVM.
4. Pilot the managed policy through the target enterprise's actual MDM/GPO tooling and run
   clean-machine install, upgrade, rollback, proxy, offline, and least-privilege scenarios.
5. Add and certify secondary architectures required by customers (macOS Intel, Windows
   ARM64, Linux ARM64) and run longer soak/load tests before an enterprise SLA is offered.
6. Establish the operational side of GA: vulnerability triage staffing, support ownership,
   release cadence, compatibility window, incident response exercises, and rollback drills.

Until items 1–4 are complete, the correct status is **release candidate**, not a signed
enterprise GA release.
