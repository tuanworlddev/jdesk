# Architecture remediation verification - 2026-07-12

This run closes the architecture review findings around update integrity, IPC resource
ownership, activation threading, optional automation, compiler policy, and release gates.

## Implemented controls

- Update activation re-hashes the bytes while staging. `VerifiedUpdate` has no public
  constructor, versions are immutable, concurrent processes use a file lock, and one
  atomically replaced manifest owns the current/previous state.
- Native WebView callbacks enqueue into a bounded serial ingress before JSON parsing.
  Stream operations use the invocation quota and worker executor; active streams are
  capped and a late BinaryStream is never opened after timeout/cancel wins.
- Frontend events hold an in-flight permit until their actual `CompletionStage`
  terminates, including handlers that ignore timeout interruption.
- Secondary-instance arguments and OS deep links share one serial non-UI activation
  dispatcher. Single-instance state is owner-only before the token is written and all
  socket reads/connects have deadlines.
- HTTP automation moved from `jdesk-runtime` to optional `jdesk-automation`. Production
  runtime images omit both `dev.jdesk.automation` and `jdk.httpserver`.
- Production Java sources use `-Werror`; only the intentional FFM `restricted` lint
  category is excluded. Release publication is downstream of the complete CI matrix for
  the exact tag SHA.

## Local verification

Environment: macOS 26.5.1 ARM64, OpenJDK 25.0.3, real system WKWebView.

| Gate | Result |
| --- | --- |
| `./gradlew check --rerun-tasks --no-build-cache` | PASS, 79 tasks executed, coverage gates PASS |
| `npm ci && npm test` in `js/jdesk-client` | PASS, 3/3 |
| Real native smoke + stress | PASS, evidence `1783846302-deb600a45fd34b54` |
| IPC stress | PASS, 10,000 round trips, 0 mismatches, 13,698.6/s |
| Real security probe | PASS, evidence `1783846340-4e4d073ef0fe9923` |
| Real `jpackage` app-image launch | PASS, evidence `1783846386-80e272ca231af1e7` |
| Evidence verifier for package run | PASS, 0 problems |
| Real unsigned DMG creation | PASS, 28 MiB, SHA-256 `c34a706ca24ee27a2b53fef5b98556ff5d563d98b86e0327f357072f03bd6294` |

The first post-split native run, `1783846248-e66c4d4704dd6d84`, failed automation DTO
serialization because the new module did not open its DTO package to Jackson. The module
descriptor was corrected and the complete native run was repeated; the PASS evidence
above is from the rerun, not from a partial retry.

## Remote verification

`.github/workflows/ci.yml` contains native, security, and package jobs for Windows x64,
Linux x64, and macOS ARM64 plus a single `release-gate`. Record the resulting run id here
after the remediation commit is exercised on GitHub-hosted runners.
