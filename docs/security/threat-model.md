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
before payload deserialization and before user code (`CapabilityEngine`, spec 12.1). The
frontend is never trusted to enforce anything.

This model maps directly onto the JDesk core framework spec: deny-by-default capabilities
(§12.1), navigation/popup restrictions (§12.2), file-access token model (§12.3), CSP and
release validation (§12.4), supply chain (§12.5), the IPC envelope and limits (§10), asset
path normalization (§9.1), and crash-diagnostic redaction (§13). Every mitigation below is
exercised on real system WebViews by `test-apps/security-probe` (§17.6) and recorded as
anti-fake evidence (§18); exit 0 is written only when every probe passes.

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

### D. Subframes and embedded content (spec 12.2)

- **Threat:** an `<iframe>` — sandboxed/opaque, or same-origin — tries to invoke
  privileged commands or read the parent's session state.
- **Mitigations:** the bridge world (`window.__jdesk`) is injected into the top frame only
  on WKWebView/WebKitGTK (`forMainFrameOnly`), and the per-navigation nonce is delivered
  to the top frame only on all engines. A sandboxed opaque-origin frame cannot read the
  parent's nonce (cross-origin `SecurityError`) and cannot mint a valid invoke. Even a
  **same-origin** subframe that reaches the bridge with the parent's *valid* nonce is still
  gated: capability evaluation denies any command the window was not granted, and the
  handler never runs.
- **Probed:** `sandboxed-iframe-no-bridge` (opaque frame, forged nonce, `ranPrivileged`
  stays false) and `same-origin-iframe-capability-denied` (same-origin frame reaches the
  bridge with a valid nonce, invokes an ungranted command, receives `CAPABILITY_DENIED`,
  `ranDenied` stays false). The latter honestly documents that the enforced boundary at the
  IPC layer is the capability gate, not per-frame origin attestation (see residual risks).

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

### H. Update and diagnostic supply chain

- **Threat:** a network or local attacker serves an old, altered or oversized update,
  redirects a download, or mutates an activated payload.
- **Mitigations:** strict signed manifest and package signatures, semantic-version
  downgrade policy, HTTPS-only transport, no redirects or content encoding, exact size
  and streaming limits, owner-only state, immutable version directories, SHA-256 before
  every launch, pending health confirmation and atomic rollback.
- **Threat:** a support archive leaks credentials or collects unrelated customer data.
- **Mitigations:** support bundles are explicit and local-only, accept only named log
  files, skip symlinks, enforce per-file and total limits, collect allowlisted system
  metadata and redact common credential and path forms.

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

## Residual risks (honest)

These are known, accepted for v1, and documented rather than hidden.

1. **Same-origin content inherits full app authority — by design.** Any script executing in
   the top-level app-origin document (or a same-origin subframe it embeds) can reach the
   bridge and call every capability that window holds. There is no in-document sandbox
   distinguishing "the app's own code" from injected same-origin script, and — as the
   `same-origin-iframe-capability-denied` probe shows — the IPC layer cannot tell a
   same-origin subframe apart from the top frame once it presents a valid nonce. The
   enforced control is therefore the **per-window capability gate**, not per-frame origin
   attestation. XSS in the frontend is a capability-scoped compromise; the defenses are the
   strict CSP (§12.4), minimizing granted capabilities per window (see the capability
   guide), and shipping no dangerous default capabilities. Treat every granted capability as
   reachable by any script that runs in that window.

2. **WKWebView custom-scheme secure-context limitations.** WKWebView (and WebKitGTK) do not
   grant the `jdesk://` custom scheme full HTTPS "secure context" behavior; some powerful web
   features gate on secure contexts and may be unavailable. JDesk does not use private
   selectors to work around this (ADR-003/ADR-004). Features unavailable under the production
   origin must be provided by native plugins or documented as unsupported. Related: CSP
   inheritance and sandbox semantics for `srcdoc`/opaque frames vary by engine, which is why
   the sandboxed-iframe probe records *which* path executed rather than asserting one
   engine's behavior.

3. **The bridge primitive is visible to page JS — by design.** `window.__jdesk.post` and the
   underlying platform primitive (`webkit.messageHandlers.jdesk` / `chrome.webview`) are
   reachable from page script; the bridge must be callable. Security does not rest on hiding
   the primitive — it rests on the nonce, capability, origin, and envelope checks at the
   Java boundary. An attacker who can call `post` still cannot mint a valid privileged invoke
   without the current nonce and a granted capability. Bridge isolation into a separate
   script world (e.g. WKContentWorld) is applied where the platform supports it; where it is
   not, isolation relies on CSP plus the capability gate.

4. **No cryptographic signature on IPC messages.** Bridge messages are not signed or MACed.
   Within a single process this is acceptable — the per-navigation nonce provides
   unforgeability against cross-document replay, and there is no network transport to
   intercept — but integrity depends entirely on the in-process boundary, not on message
   authentication.

5. **Subframe origin is not distinguished at the IPC layer in v1.** The dispatcher gates on
   the window's committed top-level origin, not a per-message frame origin. If a future
   platform exposes per-frame provenance for bridge messages, rejecting non-main-frame
   senders would be a defense-in-depth improvement.

6. **DevTools-off is config-level, not engine-asserted.** The probe verifies the production
   `devMode=false` flag (from which adapters disable DevTools); it does not programmatically
   prove the OS WebView exposes no DevTools affordance. Per-platform manual verification is
   required and should be recorded in the release checklist.

7. **Future filesystem/shell plugins are the next high-value boundary.** v1 ships none. When
   added they must implement the §12.3 opaque-scoped-token model (canonical target, allowed
   operations, window/session binding, expiry, optional single-use) and defend against
   traversal, symlink escape, token replay, and TOCTOU. This threat model must be extended
   when those plugins land.

Out of scope for v1 (§2.3): a fully compromised host OS or JVM, physical access, malicious
platform WebView binaries, and side channels within the OS WebView engine itself.
