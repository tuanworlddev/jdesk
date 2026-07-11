# ADR-008: RSS baselines and the leak regression threshold

Status: accepted (spec section 17.5)
Date: 2026-07-11

## Context

Spec section 17.5 requires: on every primary architecture, run ≥25 window create/close
cycles and ≥10,000 small IPC round trips, verify pending request/callback/window counters
return to zero, and record process RSS before and after a forced stabilization interval.
It also says: "do not impose an arbitrary memory threshold until a baseline exists; record
the baseline, then set a regression threshold in a committed follow-up ADR." This is that
follow-up ADR.

## Recorded baselines

Measured by the stress profile of `test-apps/native-smoke` (`RssSampler`, written into each
run's `environment.json`). All runs completed the full stress load (10,000 IPC round trips,
0 mismatches; 25/25 window create/close cycles) with pending invocation, callback, and
window counters returning to zero.

| Platform | Evidence run | RSS at startup | RSS after stress + stabilize |
| --- | --- | --- | --- |
| Windows x64 (WebView2) | CI 29137919391 | 77,111,296 B (~74 MiB) | 181,665,792 B (~173 MiB) |
| macOS ARM64 (WKWebView) | local 1783741637 | recorded in evidence | recorded in evidence |
| Linux x64 (WebKitGTK) | CI 29139086672 | ~80.9 MB | ~373.6 MB |

The post-stress figure includes the system WebView's own renderer/browser process memory
attributed to the launching process tree on some platforms; it is a whole-process-tree
number, not a JVM-heap number, and differs by engine. That is expected and is why an
absolute cross-platform threshold is not meaningful.

## Decision

1. **The leak signal is the counters, not the RSS number.** A run fails if pending
   invocation, callback, or window counters are non-zero after the stabilization interval.
   This is already asserted (`java:zero-pending-invocations`, `java:single-window-open`) and
   is the authoritative leak gate.

2. **Per-platform RSS regression threshold.** Once at least three green stress runs exist
   per platform in CI history, set a regression threshold at **1.5× the median
   post-stress RSS** for that platform, enforced by comparing the run's recorded
   `rss.afterProbesBytes` against a checked-in per-platform ceiling. Until three runs
   accumulate, RSS is recorded and reported but not gated (per the spec's "do not impose an
   arbitrary threshold" rule). The initial ceilings implied by the baselines above:
   Windows ≈ 260 MiB, Linux ≈ 560 MB (to be finalized from the CI median, not this single
   sample).

3. **Repeated-run growth check.** A stronger future signal is running the stress profile N
   times in one process and asserting RSS does not grow monotonically beyond the threshold
   across iterations; this is a planned enhancement to the stress harness.

## Consequences

- The counters-return-to-zero gate is live now and blocks merges on a real leak.
- The RSS threshold is deliberately not yet enforced as a hard gate; enabling it is a small
  follow-up once CI has accumulated the per-platform medians. This ADR records the baselines
  and the formula so that step is mechanical, not a fresh judgment call.
