# ADR-006: Asynchronous message passing

Status: accepted (spec section 3, ADR-006)
Date: 2026-07-11

## Decision

The WebView never receives Java objects. All IPC uses versioned JSON envelopes
(protocol v1, spec section 10) over the platform bridge, asynchronously.

- Native UI threads never execute application commands and never block on them.
- Commands run on virtual threads by default; CPU-bound work uses a bounded executor.
- Responses marshal back through `UiDispatcher`.
- Limits (1 MiB message, 128 in-flight, 30 s default timeout, 256 queued events,
  128-char names) are enforced before user code runs; cancellation is best-effort
  with exactly one terminal result per request.
