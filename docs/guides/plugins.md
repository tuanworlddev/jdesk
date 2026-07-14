# Plugins

JDesk plugins are ordinary Java modules that extend an app with native capability. Because they run
in the same JVM, the framework's job is not to *load* arbitrary code loosely but to make loading it
**safe and explicit** — signed, integrity-checked, and granted only the capabilities the app opts
into. The model lives in `jdesk-plugin` (`dev.jdesk.plugin`).

> **Status.** This is the security core (manifest + authorization + integrity), fully unit-tested.
> Wiring a verified plugin jar onto the module path at runtime is a further, app-driven step.

## Plugin manifest

A plugin ships a `PluginManifest` (JSON) declaring its identity, the exact capabilities it needs, an
integrity hash of its jar, and an optional Ed25519 signature:

```json
{
  "pluginId": "dev.acme.fs",
  "version": "1.2.0",
  "capabilities": ["fs:read", "fs:write"],
  "sha256": "<hex of the plugin jar>",
  "signature": "<optional base64 Ed25519 over the jar>"
}
```

Parse it with `PluginManifest.parse(json, maxBytes)` — it validates the id, hash, and signature
shapes and rejects unknown fields.

## Deny-by-default capabilities

Merely adding a plugin grants it nothing (Tauri's ACL model). The app passes the set of capabilities
it chooses to grant; `PluginAuthorization` refuses a plugin that declares anything ungranted:

```java
PluginAuthorization.authorize(manifest, Set.of("fs:read")); // throws — plugin also needs fs:write
```

Use `isAuthorized(...)` / `ungrantedCapabilities(...)` to decide interactively instead of throwing.

## Signed, integrity-checked loading

Before a plugin jar is ever put on a class/module path, verify its bytes:

```java
PluginIntegrity.verify(pluginJar, manifest, trustRoot);
```

The jar must hash to the manifest's `sha256`; if the manifest is signed, an Ed25519 signature over
the same bytes must verify under `trustRoot` (a signed manifest with no trust root is refused, never
trusted). Enforcing **signed plugins with integrity hashes** is something even Tauri's registry does
not require — a tampered or substituted jar is rejected rather than loaded.
