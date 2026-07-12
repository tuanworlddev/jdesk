# JDesk implementation report

Remediation commit: cd962ff (framework-gaps-macos)
Version: 0.1.0-SNAPSHOT
Date: 2026-07-11 (UTC)

## Status

- **Overall: INCOMPLETE FOR SIGNED RELEASE** — version 1 is functionally implemented and
  verified on real system WebViews across all three primary platforms, including the
  consolidated Windows x64, Linux x64, and macOS ARM64 CI matrix. Signed/notarized
  release packages still require account-owner credentials; CI packages are `UNSIGNED`
  by design. Everything else in the primary-platform scope is implemented and
  evidence-backed.
- **Implemented and verified:** pure Java core; Windows, macOS, and Linux adapters over
  real WebView2 / WKWebView / WebKitGTK through Java FFM; typed commands, events,
  cancellation, limits, lifecycle, capabilities through real bridges; deny-by-default
  security; codegen + TypeScript client; Gradle plugin + packaging; jlink/jpackage app
  images launched without Gradle; SBOM + checksums; security probes; stress/leak counters
  returning to zero.
- **Post-review packaging hardening:** production launchers now use JPMS
  `--module-path`/`--module`, per-platform native access and
  `--illegal-native-access=deny`. The modular path was exercised through a real macOS
  `jpackage` app image and native smoke; Windows/Linux package jobs contain the equivalent
  target-specific commands for their next CI run.
- **Not implemented / deferred:** signed+notarized release pipeline (installers build
  UNSIGNED); secondary architectures (Windows ARM64,
  macOS Intel, Linux ARM64); a dedicated performance benchmark harness.

## BLOCKED items — smallest action required from the user

These are the only Definition-of-Done items not satisfiable autonomously; each is blocked
on a credential or a cost decision that only the account owner can supply. Nothing here is
faked or worked around.

1. **Signed + notarized release packages** (§16.3, §21 signed-release gate). The signing
   hooks and installer builds exist; they produce `UNSIGNED` artifacts because no signing
   identities are present. To reach a signed release, provide as CI secrets:
   - Windows: an Authenticode code-signing certificate (`.pfx` + password) for `signtool`.
   - macOS: a Developer ID Application + Developer ID Installer certificate, plus
     notarization credentials (Apple ID, app-specific password, team id) for `notarytool`.
   - Linux: a GPG key id for package/repository signing.
   Then set `jdesk { signing { ... } }` and enable signing in the package/installer CI
   steps. Attempted alternative: self-signed certs — rejected, they do not satisfy a real
   signed-release gate and would be dishonest to label as signed.

The remaining "deferred" items (secondary architectures and benchmark
harness) are out of the section-26 v1 DoD and are genuine future work, not blockers.

## Real verification matrix

| OS/arch | Native smoke | Package smoke | Security probes | Evidence / CI | WebView |
| --- | --- | --- | --- | --- | --- |
| Windows x64 | PASS | PASS | PASS | Consolidated CI run 29187403208, artifacts `windows-x64-native-evidence`, `package-windows-x64-evidence`, `security-windows-x64-evidence` | WebView2 Evergreen (Windows Server 2025) |
| macOS ARM64 | PASS | PASS | PASS | Local remediation runs 1783846302 / 1783846340 / 1783846386 plus consolidated CI run 29187403208; verifier green | WKWebView (system WebKit) |
| Linux x64 | PASS | PASS | PASS | Consolidated CI run 29187403208, artifacts `linux-x64-native-evidence`, `package-linux-x64-evidence`, `security-linux-x64-evidence` | WebKitGTK 4.1 under Xvfb |

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

# Real GitHub Actions runners (windows-latest / ubuntu-latest / macos-14):
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
| `sbom.cyclonedx.json` | `ReleaseArtifacts.writeSbom` (CycloneDX 1.7) in `jdeskPackage` | n/a | Yes — deterministic, unit-tested |
| Maven artifacts + sources/javadoc | `maven-publish` per module | UNSIGNED | Build-verified |

## Performance samples

Recorded in evidence `environment.json` per run (baselines only, no advertised targets
per spec 20). Example (macOS ARM64, stress run 1783741637): 10,000 IPC round trips in
509 ms; process RSS baseline recorded. Windows/Linux stress timings above. Full raw
samples live in each run's evidence directory; a dedicated benchmark harness (cold start,
handshake latency, p50/p95/p99 IPC) is a documented follow-up.

## Blockers and unverified claims

- **Signed release** not produced: signing hooks exist as configuration surface only; CI
  packages are `UNSIGNED` and do not satisfy a signed-release gate (spec 16.3).
- **Installers build UNSIGNED.** `jdeskInstaller` produces real DMG/MSI/DEB via jpackage
  (verified: 34 MB DMG locally through the plugin; MSI/DEB in CI), but without signing
  identities they are `UNSIGNED` and do not satisfy a signed-release gate.
- No other unverified pass claims: every green cell above links to machine-generated
  evidence or a real CI run on the stated commit range.
