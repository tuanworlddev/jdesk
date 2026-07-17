# JDesk verification

The authoritative product state is [STATUS.md](STATUS.md), and release completion criteria are in
[ROADMAP.md](ROADMAP.md). This file defines where current proof is found.

## Current proof

| Gate | Current evidence |
| --- | --- |
| Complete primary-platform CI | [run 29519572858](https://github.com/tuanworlddev/jdesk/actions/runs/29519572858), commit `af9a4c8` |
| CodeQL Java/Kotlin and JavaScript/TypeScript | [run 29519572747](https://github.com/tuanworlddev/jdesk/actions/runs/29519572747), commit `af9a4c8` |
| Release workflow behavior | [run 29519900523](https://github.com/tuanworlddev/jdesk/actions/runs/29519900523), commit `af9a4c8` |

The CI workflow uploads machine-generated evidence separately for real WebView native, security and
package jobs on Windows x64, macOS ARM64 and Linux x64. Evidence includes environment/provider facts,
case results, snapshots, checksums and verifier output. No fake provider may satisfy a native gate.

## Reproducing checks

```bash
./gradlew build --stacktrace
npm ci --prefix js/jdesk-client
npm test --prefix js/jdesk-client
python3 scripts/verify_release_versions.py
```

Native evidence commands and anti-fake rules are documented in
[`docs/verification/native-testing-and-evidence.md`](docs/verification/native-testing-and-evidence.md).
Public release consumption is tested by `.github/workflows/public-canary.yml`; unlike repository CI,
that workflow may resolve only Maven Central, the Gradle Plugin Portal and npm.

## Historical reports

Dated investigations under [`docs/verification/`](docs/verification/) describe the commit and
environment they tested. They are useful engineering records but do not override current CI or
prove later commits. Superseded root-level audit snapshots were removed to prevent stale
`NOT_IMPLEMENTED` rows from being mistaken for current state; they remain available in Git history.
