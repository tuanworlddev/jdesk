# JDesk implementation report

Commit: 2365f051d0d68eb2ffbc6a1e14cb513d1d4827b7 (main)
Version: 0.1.0-SNAPSHOT
Date: 2026-07-11 (UTC)

## Status

- **Overall: INCOMPLETE** — version 1 is functionally implemented and verified on real
  system WebViews across all three primary platforms, but two Definition-of-Done items
  (section 26) are not yet fully satisfied on a single consolidated CI commit: (a) a
  macOS native/package CI leg (macOS is verified on **real local Apple Silicon
  hardware**, not yet a private-repo CI runner), and (b) signed release packages (CI
  packages are `UNSIGNED` by design). Everything else is implemented and evidence-backed.
- **Implemented and verified:** pure Java core; Windows, macOS, and Linux adapters over
  real WebView2 / WKWebView / WebKitGTK through Java FFM; typed commands, events,
  cancellation, limits, lifecycle, capabilities through real bridges; deny-by-default
  security; codegen + TypeScript client; Gradle plugin + packaging; jlink/jpackage app
  images launched without Gradle; SBOM + checksums; security probes; stress/leak counters
  returning to zero.
- **Not implemented / deferred:** signed+notarized release pipeline (installers build
  UNSIGNED); named-module native-access runtime images (uses
  `--enable-native-access=ALL-UNNAMED` fallback); secondary architectures (Windows ARM64,
  macOS Intel, Linux ARM64); a project-generator for the basic/structured templates; a
  dedicated performance benchmark harness.

## Real verification matrix

| OS/arch | Native smoke | Package smoke | Security probes | Evidence / CI | WebView |
| --- | --- | --- | --- | --- | --- |
| Windows x64 | PASS | PASS | PASS | CI runs 29137796715 / 29137919391 (stress) / 29139506086 (package) / 29140030589 (security), artifacts `windows-x64-native-evidence`, `package-windows-x64-evidence`, `security-windows-x64-evidence` | WebView2 Evergreen (Windows Server 2025) |
| macOS ARM64 | PASS (local) | PASS (local) | PASS (local) | Local real hardware, runs 1783741626 / 1783741637 (stress) / 1783741694 (package) / 1783744909 (security); archived `~/JDesk-evidence-archive`; verifier green | WKWebView (system WebKit, macOS 26.5.1) |
| Linux x64 | PASS | PASS | PASS | CI run 29139086672 (native) + 29139506086 (package) + 29140030589 (security), artifacts `linux-x64-native-evidence`, `package-linux-x64-evidence`, `security-linux-x64-evidence` | WebKitGTK 4.1 under Xvfb |

All native/package evidence is machine-generated per section 18, validated by
`EvidenceVerifier` (checksum recomputation, schema/timestamp checks, and rejection of
`fake`/`mock`/`unknown` providers for native/package categories). No provider id in any
passing run is a fake.

## Commands actually executed

```text
# Local (macOS arm64), real WKWebView:
./gradlew check                                                    # 602 unit/functional tests
./gradlew --no-configuration-cache :test-apps:native-smoke:run -PjdeskPlatform=macos
./gradlew --no-configuration-cache :test-apps:native-smoke:run -PjdeskPlatform=macos -PjdeskStress=true
./gradlew --no-configuration-cache :test-apps:security-probe:run -PjdeskPlatform=macos
jpackage --type app-image ... && ./JDeskSmoke.app/Contents/MacOS/JDeskSmoke   # package smoke
./gradlew :test-apps:native-smoke:verifyEvidence                  # anti-fake verification

# Real GitHub Actions runners (windows-latest / ubuntu-latest):
./gradlew --no-configuration-cache :test-apps:native-smoke:run -PjdeskPlatform=<windows|linux> -PjdeskStress=true
./gradlew --no-configuration-cache :test-apps:security-probe:run -PjdeskPlatform=<windows|linux>
jpackage --type app-image ... && <launch app image without Gradle>   # package smoke
./gradlew :test-apps:native-smoke:verifyEvidence -PjdeskEvidenceVerifyDir=...
```

## Test counts and raw reports

- Unit / functional: **602** tests, 0 failures (jdesk-api 107, jdesk-runtime 359,
  jdesk-native-ffm 27, jdesk-codegen 46, jdesk-packager 22, jdesk-gradle-plugin 13
  incl. 11 TestKit functional, jdesk-webview-spi 9, jdesk-testkit 12, security-probe 7).
  Coverage: jdesk-api 89.7%/89.6%, jdesk-runtime 89.4%/84.6%, jdesk-native-ffm
  97.0%/100%, jdesk-webview-spi 86.7%/100% (line/branch; JaCoCo gate 80/70).
- Native smoke: 20–22 cases per platform through the real bridge (handshake, typed echo,
  Java→JS event, non-UI-thread handler, cancellation, unknown command, capability denial
  before handler, oversize payload, 100 concurrent invokes, remote navigation blocked,
  asset 200/404/traversal-rejected, secondary window create/close/recreate, clean
  shutdown with zero pending, real snapshot validated).
- Security: all section 17.6 properties on real WebViews (22 cases) + CspValidator unit.
- Stress: 10,000 IPC round trips, 0 mismatches, per platform (Windows 5152 ms, macOS
  509 ms, Linux 6208 ms); 25/25 window create/close cycles; pending counters return to
  zero; RSS baselines recorded (no threshold yet — follow-up ADR per spec 17.5).

## Artifacts

| Artifact | How produced | Signed? | Tested? |
| --- | --- | --- | --- |
| jpackage app image (Win/mac/Linux) | `jpackage --type app-image` on the target OS | UNSIGNED | Yes — launched without Gradle, ran the native smoke, exit 0 |
| Native installer (DMG/MSI/DEB) | `jdeskInstaller` → `jpackage --type <dmg/msi/deb>` on the target OS | UNSIGNED | Created & checksummed: Windows `JDeskSmoke-1.0.0.msi` (sha256 1e651972…), Linux `jdesksmoke_1.0.0_amd64.deb` (sha256 2c12b580…) in CI run 29140603452; macOS `fresh-1.0.0.dmg` built locally through the plugin |
| `checksums.sha256` | `ReleaseArtifacts.writeChecksums` in `jdeskPackage` | n/a | Yes — 282-file image verified, unit-tested |
| `sbom.cyclonedx.json` | `ReleaseArtifacts.writeSbom` (CycloneDX 1.5) in `jdeskPackage` | n/a | Yes — deterministic, unit-tested |
| Maven artifacts + sources/javadoc | `maven-publish` per module | UNSIGNED | Build-verified |

## Performance samples

Recorded in evidence `environment.json` per run (baselines only, no advertised targets
per spec 20). Example (macOS ARM64, stress run 1783741637): 10,000 IPC round trips in
509 ms; process RSS baseline recorded. Windows/Linux stress timings above. Full raw
samples live in each run's evidence directory; a dedicated benchmark harness (cold start,
handshake latency, p50/p95/p99 IPC) is a documented follow-up.

## Blockers and unverified claims

- **macOS CI leg not run** (private-repo macOS runner minutes are 10×). macOS is verified
  on **real local Apple Silicon hardware** with archived, verifier-checked evidence, which
  satisfies spec rule 5 (real hardware). A CI leg can be enabled by uncommenting the
  documented job.
- **Signed release** not produced: signing hooks exist as configuration surface only; CI
  packages are `UNSIGNED` and do not satisfy a signed-release gate (spec 16.3).
- **Installers build UNSIGNED.** `jdeskInstaller` produces real DMG/MSI/DEB via jpackage
  (verified: 34 MB DMG locally through the plugin; MSI/DEB in CI), but without signing
  identities they are `UNSIGNED` and do not satisfy a signed-release gate.
- No other unverified pass claims: every green cell above links to machine-generated
  evidence or a real CI run on the stated commit range.
