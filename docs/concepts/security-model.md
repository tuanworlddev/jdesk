# Security model

This page explains the trust model behind JDesk and the reasoning for enforcing it in Java:
deny-by-default capabilities evaluated before your code runs, navigation and origin locking
to the app origin, popup denial, the per-navigation nonce, a strict content security policy,
asset path-traversal defenses, and error redaction. It is the understanding-oriented
companion to the [threat model](../security/threat-model.md), which lists the concrete
adversaries, mitigations, and residual risks. This page gives the rationale and trade-offs;
that page gives the matrix. For the how-to, see
[Capabilities and permissions](../guides/capabilities-and-permissions.md) and the
[capabilities JSON reference](../reference/capabilities-json.md).

## The trust boundary is the Java side

JDesk treats the system WebView as a place where untrusted things can happen, and the Java
core as the only place where authority lives. There are three trust levels
([threat model](../security/threat-model.md)):

- **The Java core is trusted.** Framework and application code, full JVM authority; native
  access is granted only to the platform modules.
- **The frontend document at the app origin is semi-trusted.** It is your own UI, but it is
  treated as capable of being compromised — by cross-site scripting, or by a supply-chain-
  tainted dependency — so nothing it says is taken on faith.
- **Any other origin is untrusted.** Remote pages, subframes, and popups get no native
  authority by default.

The single security boundary is the **Java-side check on every invoke**. The frontend is
never trusted to enforce anything, because a frontend that has been compromised cannot be
trusted to police itself. This is the organizing principle from which every other decision on
this page follows: if a control could be bypassed by attacker-controlled JavaScript, it is not
a control. The real controls live where the attacker's script cannot reach — in
`jdesk-runtime`.

## Deny by default, evaluated before your code

Every command is denied unless something explicitly allows it. A command must be classified at
compile time: either it carries `@RequiresCapability("name")` and runs only for windows granted
that capability, or it is marked `@PublicDesktopCommand`. A command with neither is a
compile-time error — the annotation processor refuses to build it
([ADR-005](../architecture/ADR-005-compile-time-registration.md)). Deny-by-default is therefore
not a runtime default you might forget to set; it is a property the build enforces. You cannot
ship a command that is accidentally callable.

Just as important is *when* the check runs. The capability engine evaluates the decision
**before the payload is deserialized and before your handler executes**. Walking the invoke
lifecycle (see [How IPC works](./how-ipc-works.md#the-invoke-lifecycle-step-by-step)), the
order is: registry lookup, then capability evaluation, and only *after* that, payload
deserialization and the handler. A denied command never touches your DTOs and never reaches a
line of your code. This ordering is a security property, not an optimization: it means the
attack surface of an ungranted command is essentially nil, because none of your logic — and
none of the deserialization machinery — is exercised on its behalf.

The engine's evaluation is deliberately narrow. It checks that the navigation session is
live, that the committed origin is one of the allowed origins, and that the window holds the
required capability — and then it returns a single, uniform denial for every failure:
`CAPABILITY_DENIED` with the message "Command is not allowed for this window." It never
reveals which capabilities exist or which grants are configured, so a probing frontend learns
nothing from the shape of the refusal.

Capabilities are granted per window in `jdesk-capabilities.json`. Omitting the window list
grants a capability to every window; listing windows scopes it. The design intent is
least privilege: grant each window only what it needs, because — as the residual-risks section
of the [threat model](../security/threat-model.md) states plainly — any script that runs in a
window can reach every capability that window holds. There is no in-document sandbox
distinguishing "your code" from injected same-origin script. XSS in the frontend is therefore a
*capability-scoped* compromise, and the size of that compromise is exactly the set of
capabilities you granted. Keeping that set small is the mitigation. See
[Capabilities and permissions](../guides/capabilities-and-permissions.md) for how to model
grants.

## Origin and navigation locking

Authority is bound to *where the document came from*, not merely to which window it is. Two
controls keep the app window pinned to the app origin, `jdesk://app`:

- **Origin check on every invoke.** Before a capability is even considered, the engine
  normalizes the window's committed top-level origin and requires it to be one of the allowed
  origins — the app origin in production, plus exactly one configured
  `http://127.0.0.1:<port>` dev origin in development, with no silent fallback
  ([ADR-004](../architecture/ADR-004-no-localhost-production.md)). Origins are compared in
  canonical form — lowercased scheme and host, default ports elided, paths and userinfo
  rejected — so raw string equality is never the deciding factor.
- **Navigation policy.** Production main-frame navigation is restricted to the allowed
  origins; a main-frame navigation to a remote origin is blocked and logged. This stops the
  classic escalation where a script sets `location` to an attacker page to inherit the bridge,
  or a link opens hostile content in the app window. Subframe loads are allowed but receive no
  native authority — the bridge origin check remains the boundary.

The two together mean that even if hostile content somehow loaded, it would sit at a
disallowed origin and every invoke from it would be denied before any command ran.

### Popups are denied

New-window and popup requests are denied by default at the adapter level. On macOS, for
example, the navigation delegate treats a navigation action with no target frame as a
popup and cancels it. A popup is a new top-level context that could be pointed at arbitrary
content while still feeling "part of the app," so refusing it by default removes a whole class
of confusion. Opening an external link is possible, but only through an explicit shell or
browser capability that is not enabled by default — an affirmative choice, never an ambient one.

## The nonce lifecycle

Origin locking answers "where is this document from"; the per-navigation nonce answers "is
this message from the document that is loaded *right now*." Each main-frame navigation mints a
fresh 128-bit random nonce, delivered to the top frame only, and every envelope from the client
must echo it. A stale or wrong nonce can never reach command execution: an `invoke` with a
stale nonce fails with `STALE_NONCE`, and a stale `hello` or `cancel` is ignored. On
navigation the previous session is invalidated, its in-flight work is cancelled after a grace
period, and its late results are dropped so nothing from the old document reaches the new one.

The security value is unforgeability against cross-document replay within the process. A late
asynchronous result from a page you have navigated away from cannot act on the new page; a
sandboxed opaque-origin subframe cannot read the top frame's nonce across the origin boundary,
so it cannot mint a valid invoke. The nonce is covered mechanically in
[How IPC works](./how-ipc-works.md#the-per-navigation-nonce-and-why-it-exists); the honest
limit worth restating is that it is an in-process integrity mechanism, not a cryptographic
signature — there is no MAC on bridge messages, which is acceptable precisely because there is
no network transport to intercept and the nonce already defeats cross-document replay.

## Strict CSP

The default content security policy is deliberately tight:
`default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`. It blocks
inline script and `eval`, forbids plugins and `<base>` hijacking, and prevents the app from
being framed. CSP is a defense-in-depth layer, not the primary boundary — the capability gate
is — but it meaningfully raises the cost of turning an injection into code execution.

Because CSP only helps if it is actually strict, release builds *screen* it: a policy that
weakens script safety with `'unsafe-inline'`, `'unsafe-eval'`, or `'unsafe-hashes'` is
**rejected** unless the developer sets an explicit, named acknowledgement option that then
appears in the build report. The trade-off is intentional. Some frontend stacks genuinely need
a looser policy, so JDesk does not forbid it outright; but it refuses to let the weakening
happen silently, forcing it to be a visible, on-the-record decision rather than a default that
quietly erodes.

## Asset path-traversal defenses

Assets load over `jdesk://app/` through each platform's resource-interception API — there is
no localhost HTTP server in production ([ADR-004](../architecture/ADR-004-no-localhost-production.md)).
The asset resolver is a place where attacker-influenced input (a URL path) meets the
filesystem, so it is strict by construction. Path normalization **rejects rather than
repairs**: `..` segments, encoded and double-encoded traversal, NUL and control characters,
backslashes, colons, absolute and drive forms, invalid percent-encoding, empty segments, and
ambiguous trailing-dot or trailing-space names all fail. Directory-backed sources additionally
enforce symlink containment on the real resolved path. A rejected request gets a deterministic
404 with **no echo of the input path** — the response never confirms what was asked for.

Rejecting rather than sanitizing is the safer discipline: a normalizer that tries to "clean"
a malicious path is a rich source of bypasses, because attackers compete to find an encoding
the cleaner mangles into something dangerous. Refusing anything that is not already a plain,
canonical relative path removes that game entirely.

## Error redaction

Nothing sensitive crosses back to the frontend in an error. An unexpected handler exception
becomes `INTERNAL_ERROR` with the message "Command failed" — never a class name, stack trace,
filesystem path, SQL fragment, secret, or internal exception text. Every error the frontend
sees is one of the public `ErrorCode` names with a generic message.

This is defense against reconnaissance. A compromised frontend that could read stack traces or
paths would learn about your code's structure, your dependencies, and your filesystem layout —
exactly the map an attacker wants. Redaction is a real usability trade-off: it makes remote
debugging harder, since the frontend cannot see why a command failed. JDesk accepts that cost
and keeps the diagnostic detail on the Java side, in logs, where it is useful to you and
invisible to an attacker.

## What this model does not claim

Being honest about the boundary is part of the model. The [threat model](../security/threat-model.md)
documents the residual risks in full; the ones most worth internalizing here are that
same-origin content inherits the full authority its window holds (so minimizing granted
capabilities is load-bearing), that the bridge primitive is intentionally visible to page
script (security rests on the checks, not on hiding it), and that subframe origin is not
distinguished at the IPC layer in v1 (the enforced control is the per-window capability gate,
not per-frame attestation). Out of scope entirely are a compromised host OS or JVM, physical
access, and malicious WebView binaries. Filesystem and shell plugins — the next high-value
boundary — are not part of the v1 core; when they land they must implement the scoped-token
model the threat model describes, and that document will be extended to cover them.

## Related reading

- [Threat model](../security/threat-model.md) — the adversary matrix, mitigations, probes,
  and residual risks.
- [How IPC works](./how-ipc-works.md) — the invoke lifecycle and where the capability check
  sits in it.
- [Capabilities and permissions](../guides/capabilities-and-permissions.md) and the
  [capabilities JSON reference](../reference/capabilities-json.md) — how to declare and grant
  capabilities.
- The [verification matrix](../../VERIFICATION.md) — which security probes pass on which
  platforms.
