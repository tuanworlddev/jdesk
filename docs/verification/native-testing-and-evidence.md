# Native Testing and Evidence

JDesk treats "it works" as a claim that must be backed by **machine-generated evidence**
from a real native run — never a hand-edited status. This page explains the evidence
system ([spec sections 17.3, 18](../../JDESK_CORE_FRAMEWORK_SPEC.md)), the probe suites,
and how to run them. The source of truth for what is actually verified is
[../../VERIFICATION.md](../../VERIFICATION.md) and
[../../IMPLEMENTATION_STATUS.md](../../IMPLEMENTATION_STATUS.md).

## Why

A scaffold must never be able to fake a green result. Two rules enforce this:

- **No fake providers.** A `native`/`package`/`release` evidence run must report a *real*
  platform provider id (`windows-webview2`, `macos-wkwebview`, `linux-webkitgtk`).
  Provider ids that are empty, `unknown`, or contain `fake`/`mock`/`headless-fake` are
  rejected by the verifier.
- **PASSED is derived, not asserted.** The manifest starts `INCOMPLETE`; `PASSED` is only
  written after every recorded case passed **and** exit code 0 **and** snapshot
  validation. A crashed/abandoned run stays `INCOMPLETE` on disk.

## Evidence directory layout

Every native or package run writes one directory under `build/evidence/<run-id>/`
(`EvidenceRun`, in `jdesk-testkit`):

```text
build/evidence/<run-id>/
├── manifest.json        # schema version, run id, UTC start/end, git commit + dirty,
│                        #   OS/arch, JDK, framework version, provider id, WebView version,
│                        #   category, exact command, PIDs, exit code, per-file SHA-256,
│                        #   derived overall status
├── environment.json     # os/jdk/git environment snapshot
├── results.json         # every case: name, passed, detail + overall status/exitCode
├── app.log              # timestamped framework/app log incl. PASS/FAIL lines
├── stdout.log           # captured stdout
├── stderr.log           # captured stderr
├── screenshot.png       # real WebView snapshot (validated, see below)
├── checksums.sha256     # SHA-256 of every other file (GNU coreutils format)
└── junit.xml            # JUnit-format case results
```

The run id is `<epoch-seconds>-<random hex>` generated at startup. `manifest.json` is
written first as `INCOMPLETE`, then rewritten with the derived status and file hashes at
`finish(exitCode)`.

### Snapshot validation

The WebView is captured through the platform's real snapshot/capture API and validated by
`PngValidator`: decodable, expected dimensions, not blank, non-uniform pixels. OCR is
optional and never the sole assertion.

## Verification (anti-fake) — `EvidenceVerifier` / `VerifyMain`

`jdeskVerifyEvidence` (and the test-apps' `verifyEvidence` task) run
`dev.jdesk.testkit.evidence.VerifyMain`, which for each run directory:

- checks all required manifest fields are present and `schemaVersion` matches;
- for a `PASSED` run: confirms all eight required files exist, `endedAtUtc` ≥
  `startedAtUtc`, exit code 0, every recorded case passed, and there is at least one case;
- **recomputes** `checksums.sha256` and the per-file hashes recorded in the manifest, and
  fails on any mismatch ("evidence was modified after the run");
- for `native`/`package`/`release` categories, **rejects fake provider ids**.

CI uploads the entire evidence directory even on failure. Evidence is never committed as a
substitute for rerunning.

## Probe suites

### `test-apps/native-smoke` (category `native`)

Launches a real native window with the real system WebView and runs the section-17.3
probes through the actual bridge, including: protocol handshake; JS→Java typed echo;
Java→JS event; async command on a non-UI thread; cancellation of a real sleeping command;
unknown-command rejection; capability denial *before* handler execution; oversize-payload
rejection; 100 concurrent invokes with correct ids; remote-navigation blocked; asset
200/404/traversal-rejected; secondary-window create/close/recreate cycles; clean shutdown
with zero pending invocations. The page renders `PASS <run-id>` only after all Java and JS
assertions pass. A **stress** profile (`-PjdeskStress=true`) adds ≥ 10,000 IPC round trips
and ≥ 25 window cycles (section 17.5), recording an RSS baseline (no threshold until a
committed follow-up ADR sets one).

### `test-apps/security-probe` (category `native`)

Proves the section-17.6 security properties on a real WebView: an iframe cannot invoke
privileged commands; a remote main-frame origin cannot reuse a previous bridge session; a
stale nonce is rejected; malformed JSON does not run user code; capability denial precedes
DTO handler invocation; encoded traversal cannot escape the asset root; production errors
disclose no stack traces or local paths; DevTools disabled in release; unsafe CSP
surfaced in build output (`CspValidatorReleaseTest`).

## How to run

Pick the adapter for the OS you are actually on (native runs require real hardware/CI for
that OS — you cannot fake another platform):

```bash
# Native smoke (functional)
./gradlew :test-apps:native-smoke:run -PjdeskPlatform=macos
./gradlew :test-apps:native-smoke:run -PjdeskPlatform=windows -PjdeskWebView2Loader=<path>
./gradlew :test-apps:native-smoke:run -PjdeskPlatform=linux      # under Xvfb on headless

# Stress profile
./gradlew :test-apps:native-smoke:run -PjdeskPlatform=macos -PjdeskStress=true

# Security probes
./gradlew :test-apps:security-probe:run -PjdeskPlatform=macos

# Verify whatever evidence was produced
./gradlew :test-apps:native-smoke:verifyEvidence
# or, in an application project with the plugin applied:
./gradlew jdeskVerifyEvidence
```

Useful properties: `-PjdeskEvidenceDir=<dir>` (where runs are written, default
`build/evidence`), `-PjdeskEvidenceVerifyDir=<dir>` (what `verifyEvidence` checks),
`-PjdeskWebView2Loader=<path>` (Windows loader).

## CI jobs and the current verified matrix

Independent CI jobs so one platform cannot mask another (spec section 19). Current jobs in
`.github/workflows/ci.yml`:

| Job | Purpose |
| --- | --- |
| `core-unit-jdk25` | Pure-Java unit/property tests on Linux. |
| `gradle-plugin-functional` | Gradle TestKit functional tests. |
| `windows-x64-native` | Native smoke on real WebView2. |
| `linux-x64-native` | Native smoke on real WebKitGTK 4.1 under Xvfb. |
| `security-windows-x64` / `security-linux-x64` | Security probes on real WebViews. |
| `package-windows-x64` / `package-linux-x64` | jpackage app-image built + launched without Gradle. |

The macOS legs are verified on **local real Apple Silicon hardware** (private-repo macOS CI
minutes are costly; a consolidated macOS CI job is deferred to Phase 7). Local real
hardware counts as real verification per the spec. **Consult
[../../VERIFICATION.md](../../VERIFICATION.md) for the authoritative pass/fail matrix with
run ids** — do not infer status from this page.
