# JDesk threat model and capability guide

This document states what JDesk defends against, how, and where the boundaries are. It
is descriptive of the implemented behavior verified by the security probes (spec 17.6),
not aspirational.

## Assets

- The host machine and user account the application runs as.
- Application-private capabilities (filesystem scopes, shell, plugin actions).
- The IPC channel between the web frontend and the Java core.
- Local application assets served over `jdesk://app/`.

## Trust boundaries

1. **Java core (trusted)** — application and framework code, full JVM authority, native
   access granted only to platform modules.
2. **Frontend document at the app origin (semi-trusted)** — the developer's own UI. It
   may call only the commands its window is granted. It is treated as capable of being
   compromised by injected content, so every request is authorized server-side.
3. **Any other origin (untrusted)** — remote pages, subframes, popups. Denied native
   authority by default.

The security boundary is the **Java-side capability check**, evaluated on every invoke
before payload deserialization and before user code (`CapabilityEngine`). The frontend
is never trusted to enforce anything.

## Adversary model and mitigations

### A. Malicious or injected script in the app document

- **Threat:** XSS or a supply-chain-compromised frontend dependency runs attacker JS in
  the app origin and tries to reach privileged commands.
- **Mitigations:**
  - Deny-by-default capabilities: a command runs only if its window is explicitly
    granted the required capability (or is `@PublicDesktopCommand`). Ungranted →
    `CAPABILITY_DENIED`, before deserialization and before the handler.
  - Strict default CSP (`default-src 'self'; script-src 'self'; object-src 'none';
    base-uri 'none'; frame-ancestors 'none'`) blocks inline script and eval. Release
    builds reject unsafe-inline/eval CSP unless the developer names an explicit
    acknowledgement option that appears in the build report (`CspValidator`).
  - No secrets, stack traces, class names, or filesystem paths cross to the frontend:
    unexpected handler exceptions map to `INTERNAL_ERROR` / "Command failed".

### B. Remote navigation / phishing redirect

- **Threat:** script sets `location` to a remote origin to gain the bridge, or a link
  opens attacker content in the app window.
- **Mitigations:** production main-frame navigation is restricted to the app origin
  (`NavigationPolicy`); remote main-frame navigations are blocked and logged. Popups /
  new-window requests are denied by default at the adapter level. External links require
  an explicit shell/browser capability (not enabled by default).

### C. Stale or replayed IPC session

- **Threat:** a document (or a late async result from a prior document) reuses a nonce
  to act after navigation.
- **Mitigations:** each main-frame navigation mints a fresh 128-bit nonce and
  invalidates the previous navigation session. Invokes with a stale nonce →
  `STALE_NONCE`; outstanding requests are cancelled after a grace period; late results
  are dropped and never delivered to the new document. Request IDs are unique within a
  session.

### D. Subframes and embedded content

- **Threat:** an `<iframe>` tries to invoke privileged commands.
- **Mitigations:** subframes receive no native authority by default; capability
  evaluation still gates every command on the window's granted set, so an ungranted
  command is denied regardless of frame. Subframe navigations are policy-checked;
  new-window requests are denied.

### E. Malformed or oversized IPC

- **Threat:** malformed JSON or oversized payloads used to crash the runtime or smuggle
  execution.
- **Mitigations:** strict envelope parsing (closed field sets per kind, unknown-field
  rejection, bounded depth/string/number sizes), 1 MiB message cap, 128 in-flight cap,
  30 s command timeout, bounded event queues. Malformed input never reaches user code
  and never yields a success result.

### F. Asset path traversal

- **Threat:** `jdesk://app/../..` style requests to read files outside the asset root.
- **Mitigations:** strict path normalization rejects `..`, encoded traversal,
  double-encoding, NUL, backslash, absolute/drive forms, invalid percent-encoding, and
  control characters; directory sources additionally enforce symlink containment on the
  real resolved path. Rejected requests get a deterministic 404 with no path echo.

### G. Native memory safety

- **Threat:** use-after-free or double-free across the FFM boundary corrupts memory.
- **Mitigations:** the handle state machine makes close idempotent and rejects
  post-close operations; the callback registry pins Java targets, method handles, upcall
  stubs, and owning arenas, unregisters in reverse order, and gates late callbacks
  (returning safely instead of dereferencing freed memory). Arenas are closed only after
  in-flight callbacks drain; a straggler leaks the arena rather than freeing under a live
  callback.

## Capability guide

Capabilities are declared per command with `@RequiresCapability("name")` and granted per
window in `jdesk-capabilities.json`:

```json
{
  "version": 1,
  "grants": [
    { "capability": "greeting:use", "windows": ["main"] },
    { "capability": "clipboard:read" }
  ]
}
```

- Omitting `windows` grants to every window; listing windows restricts the grant.
- A command with neither `@RequiresCapability` nor `@PublicDesktopCommand` is a
  compile-time error (deny by default is enforced by the annotation processor).
- File access does not accept arbitrary frontend paths as authority: file-dialog
  selections return opaque scoped tokens bound to a canonical target, allowed operations,
  window/session, and expiry (filesystem plugin; not part of the v1 core surface).

## Known limitations (honest)

- WKWebView does not grant custom schemes full HTTPS secure-context behavior; JDesk does
  not use private selectors to work around this (ADR-004). Web features unavailable under
  the production origin must be provided by native plugins or documented unsupported.
- Bridge isolation into a separate script world is applied where the platform supports it
  (e.g. WKContentWorld); on engines without it, isolation relies on CSP + capability
  gating.
- Subframe origin is not distinguished at the IPC layer in v1; the enforced control is
  the capability gate plus deny-by-default subframe authority, not per-frame origin
  attestation.
