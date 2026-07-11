# `jdesk-capabilities.json` reference

The capability file declares which [capabilities](java-api.md#capabilities) are granted to
which windows. It is loaded by the runtime into a
[`CapabilitySet`](java-api.md#capabilityset) and evaluated before every command runs.
The model is deny-by-default: a capability not listed here is denied.

Loaded by `dev.jdesk.runtime.config.Capabilities`. Parse failures throw
[`JDeskException`](java-api.md#jdeskexception) with `ILLEGAL_STATE` and abort startup —
a typo must fail startup, not silently widen or narrow permissions.

## Schema

```json
{
  "version": 1,
  "grants": [
    { "capability": "greeting:use", "windows": ["main"] },
    { "capability": "clipboard:read" }
  ]
}
```

### Top level

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `version` | integer | yes | Must be exactly `1`. |
| `grants` | array of grant objects | yes | The list of capability grants. May be empty (grants nothing). |

The root must be a JSON object. No other top-level fields are allowed.

### Grant object

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `capability` | string | yes | Capability name. Must be non-blank and ≤ 128 chars. |
| `windows` | array of strings | no | Window ids the grant applies to. |

No other fields are allowed in a grant object.

## Semantics

- **Omitted `windows` = all windows.** A grant with no `windows` array applies to every
  window of the application.
- **Non-empty `windows` restricts** the grant to those window ids.
- **Deny by default.** `CapabilitySet.isGranted(capability, windowId)` returns true only
  when some grant names that capability and either has an empty window set or contains the
  window's id. Anything not matched is denied.

## Loading

`dev.jdesk.runtime.config.Capabilities` reads the file (defensive JSON parsing with the
default [JSON limits](#json-limits)) and returns a `CapabilitySet`:

| Method | Meaning |
| --- | --- |
| `static CapabilitySet fromResource(String resourceName)` | Loads via the current thread's context class loader. |
| `static CapabilitySet fromResource(String resourceName, ClassLoader loader)` | Loads via the given class loader. |
| `static CapabilitySet fromResource(Module module, String resourceName)` | Loads from a named application module (falls back to the module's class loader for unnamed modules). |
| `static CapabilitySet parse(String json)` | Parses a capability document from a string. |

Typical use from an application:

```java
.capabilities(Capabilities.fromResource(
    App.class.getModule(), "jdesk-capabilities.json"))
```

## Rejected inputs

Every case below throws `JDeskException(ILLEGAL_STATE, …)` and aborts loading, except the
capability-name check, which is enforced by
[`CapabilityGrant`](java-api.md#capabilitygrant) and throws `INVALID_REQUEST`.

| Condition | Message |
| --- | --- |
| Resource not found | `Capability resource not found: <name>` |
| I/O failure reading the resource | `Failed to read capability resource: <name>` |
| Not parseable as JSON | `Malformed capability JSON` |
| Root is not a JSON object | `Capability file must be a JSON object` |
| Unknown top-level field | `Unknown field in capability file: <name>` |
| `version` missing, not an integer, or not `1` | `Capability file version must be 1` |
| `grants` missing or not an array | `Capability file requires a grants array` |
| A grant entry is not an object | `Grant entries must be objects` |
| Unknown field inside a grant | `Unknown field in capability file: <name>` |
| `capability` missing or not a string | `Grant requires a capability string` |
| `windows` present but not an array | `Grant windows must be an array` |
| A window id is not a string | `Window ids must be strings` |
| `capability` blank or > 128 chars | `Invalid capability name` (`INVALID_REQUEST`) |

## JSON limits

The document is parsed with the runtime's default JSON bounds
(`dev.jdesk.runtime.json.JsonLimits.DEFAULTS`); a document exceeding them is rejected as
malformed:

| Limit | Default |
| --- | --- |
| Max nesting depth | 64 |
| Max string length | 262 144 |
| Max number length | 100 |
| Max total bytes | 1 048 576 (1 MiB) |

## Examples

Grant one capability to a single window:

```json
{
  "version": 1,
  "grants": [
    { "capability": "greeting:use", "windows": ["main"] }
  ]
}
```

Grant one capability to every window, and another to two named windows:

```json
{
  "version": 1,
  "grants": [
    { "capability": "clipboard:read" },
    { "capability": "fs:read", "windows": ["main", "settings"] }
  ]
}
```

## See also

- [Java API — Capabilities](java-api.md#capabilities) — `CapabilitySet`,
  `CapabilityGrant`, `PermissionDecision`.
- [`@RequiresCapability` / `@PublicDesktopCommand`](java-api.md#commands) — how a command
  declares its capability.
- [IPC protocol](../architecture/ipc-protocol.md) — capability evaluation happens before
  payload deserialization, failing with `CAPABILITY_DENIED`.
- [Threat model](../security/threat-model.md) — the security rationale for deny-by-default.
