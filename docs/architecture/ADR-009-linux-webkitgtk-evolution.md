# ADR-009: Linux WebKitGTK API evolution

Status: accepted
Date: 2026-07-11

## Context

The v1 Linux baseline is Ubuntu 22.04 and `webkit2gtk-4.1`. That API uses GTK 3 and
libsoup 3. WebKitGTK 6.0 uses GTK 4 and is not ABI-compatible with the windowing and
event-loop calls used by the current adapter.

## Decision

- Keep `webkit2gtk-4.1` for the v1 Ubuntu 22.04 compatibility contract. It remains a
  supported upstream API and receives WebKit security updates through the distribution.
- Do not silently load 6.0 symbols through the 4.1 adapter. The different GTK major ABI
  requires a separately tested provider.
- Add `linux-webkitgtk6` before raising the minimum Linux baseline beyond Ubuntu 22.04.
  It will share runtime/SPI code but own GTK 4 window creation, lifecycle and snapshots.
- CI must retain a 4.1 job while the v1 support window is active; a 6.0 job becomes a
  release gate when that provider is introduced.

## Consequences

This resolves the upgrade ambiguity without dropping the current LTS target or pretending
GTK 3 and GTK 4 are ABI-compatible. The existing adapter is maintenance, not the basis for
a future GTK 4 implementation.
