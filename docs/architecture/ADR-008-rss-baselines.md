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
| Windows x64 (WebView2) | CI 29187403208 | recorded in evidence | 128,561,152 B |
| macOS ARM64 (WKWebView) | CI 29187403208 | recorded in evidence | 227,213,312 B |
| Linux x64 (WebKitGTK) | CI 29187403208 | recorded in evidence | 297,611,264 B |

The post-stress figure includes the system WebView's own renderer/browser process memory
attributed to the launching process tree on some platforms; it is a whole-process-tree
number, not a JVM-heap number, and differs by engine. That is expected and is why an
absolute cross-platform threshold is not meaningful.

## Decision

1. **The leak signal is the counters, not the RSS number.** A run fails if pending
   invocation, callback, or window counters are non-zero after the stabilization interval.
   This is already asserted (`java:zero-pending-invocations`, `java:single-window-open`) and
   is the authoritative leak gate.

2. **Catastrophic RSS regression budget.** Native smoke now fails above 768 MiB after its
   stabilization interval. This deliberately loose cross-platform guardrail is above all
   recorded engine baselines and catches order-of-magnitude retention without pretending
   RSS is a precise leak metric. Per-platform rolling-median budgets remain a refinement
   once scheduled history is long enough.

3. **Latency and startup budgets.** Stress runs fail when IPC exceeds p95 150 ms or p99
   300 ms. Native ready must occur within 15 seconds. Gradle properties can override all
   four limits, and every effective value is written to evidence.

4. **Repeated-run growth check.** A stronger future signal is running the stress profile N
   times in one process and asserting RSS does not grow monotonically beyond the threshold
   across iterations; this is a planned enhancement to the stress harness.

## Consequences

- The counters-return-to-zero gate is live now and blocks merges on a real leak.
- RSS, latency and startup have hard catastrophic-regression budgets; counters remain the
  authoritative leak signal.
