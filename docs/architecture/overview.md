# Architecture Overview

JDesk is a desktop application framework built on **Java 25 + the operating-system
WebView** — the Tauri model without Rust. Application and plugin logic live in the JVM
(JPMS modules, virtual threads, FFM native access); the UI is any static web build
rendered by the platform's own WebView (WebView2 / WKWebView / WebKitGTK). There is **no
bundled Chromium, no localhost HTTP server in production, and no Rust**.

This document is the map. Deeper decisions live in the ADRs
([ADR-001](ADR-001-java25-jpms-ffm.md) … [ADR-007](ADR-007-jvm-distribution-first.md)),
the wire format in [ipc-protocol.md](ipc-protocol.md), and the security posture in
[../security/threat-model.md](../security/threat-model.md).

## Module map

| Module | Role |
| --- | --- |
| `jdesk-api` | Public, Java-only API surface: `JDeskApplication`, `WindowConfig`/`WindowId`, `CommandRegistry`/`CommandDefinition`, `InvocationContext`, `CapabilitySet`/`PermissionDecision`, `EventEmitter`/`Subscription`, `UiDispatcher`, `PlatformInfo`, lifecycle hooks, `@DesktopCommand`/`@RequiresCapability`/`@PublicDesktopCommand`, `JDeskException`/`ErrorCode`. No AWT/Swing/JavaFX, no native types. ([ADR-001](ADR-001-java25-jpms-ffm.md)) |
| `jdesk-runtime` | Pure-Java engine: lifecycle state machine, IPC protocol v1, capability engine (deny-by-default, evaluated before deserialization), asset resolver, `JsonCodec` SPI (defensive Jackson default), command dispatch onto virtual threads, limits/cancellation/backpressure. Provides the `JDeskBootstrap` that `JDeskApplication.run()` locates. |
| `jdesk-webview-spi` | The platform SPI (`PlatformProvider`, `PlatformApplication`, `PlatformWindow`, `PlatformWebView`) that runtime talks to and adapters implement. See [section 8](../../JDESK_CORE_FRAMEWORK_SPEC.md). |
| `jdesk-native-ffm` | Shared FFM helpers. The package is `dev.jdesk.ffm` (not `native`, a Java keyword) per [ADR-001](ADR-001-java25-jpms-ffm.md). Arena/callback lifetime primitives used by all adapters. |
| `jdesk-platform-windows` | Win32 + WebView2 adapter (COM, STA message pump). Provider id `windows-webview2`. |
| `jdesk-platform-macos` | AppKit + WKWebView adapter (ObjC runtime via FFM). Provider id `macos-wkwebview`. |
| `jdesk-platform-linux` | GTK 3 + WebKitGTK 4.1 adapter (GLib main context). Provider id `linux-webkitgtk`. |
| `jdesk-codegen` | Annotation processor (`DesktopCommandProcessor`): compile-time command registration → `<Service>Commands` Java registries + `types.ts`/`commands.ts`. Deterministic, byte-identical output. ([ADR-005](ADR-005-compile-time-registration.md)) |
| `jdesk-gradle-plugin` | The `dev.jdesk.application` Gradle plugin: doctor, bindings, frontend build, dev loop, runtime image, package, native smoke, evidence verify. Non-JPMS by necessity ([ADR-002](ADR-002-gradle-first.md)). See [../development/gradle-plugin-reference.md](../development/gradle-plugin-reference.md). |
| `jdesk-packager` | `jlink`/`jpackage`/`jdeps` argument builders + `ReleaseArtifacts` (SHA-256 checksums, CycloneDX SBOM). Consumed by the plugin's packaging tasks. |
| `jdesk-testkit` | Evidence system (`EvidenceRun`, `EvidenceVerifier`, `VerifyMain`, `PngValidator`, `RssSampler`) for machine-generated native/package verification ([spec 18](../../JDESK_CORE_FRAMEWORK_SPEC.md)). |
| `js/jdesk-client` | Zero-dependency TypeScript runtime for IPC protocol v1 (nonce lifecycle, `invoke`, timeout/cancel, navigation reset, events). `jdesk-codegen` emits typed wrappers that import `invoke` from it. |

Consumer-facing pieces live outside `modules/`: `examples/hello-vanilla` (the concrete
basic template) and `test-apps/native-smoke` / `security-probe` / `packaging-probe`
(real-native verification apps).

## Request/response data flow

A command round trip from the web page to Java and back:

```
JS  commands.greeting.greet({name})     // typed wrapper, @jdesk/client
      │  invoke envelope {v,kind:"invoke",id,command,payload,nonce}
      ▼
Native WebView message channel  ──►  PlatformWebView.onMessage (UI thread)
      │  copy string, dispatch OFF the UI thread immediately
      ▼
Runtime bridge / dispatcher
      │  1. validate nonce (reject stale navigation session)
      │  2. enforce limits (size ≤ 1 MiB, in-flight ≤ 128, unique id)
      │  3. capability check  ──►  CapabilitySet + PermissionDecision
      │        (window id + origin + command name; BEFORE deserialization)
      │  4. deserialize payload into the command DTO (defensive JsonCodec)
      ▼
Virtual-thread handler  (CommandDefinition → your @DesktopCommand method)
      │  returns CompletionStage<Res>
      ▼
Result envelope {v,kind:"result",id,ok,value|error}
      │  marshalled back through UiDispatcher → PlatformWebView.postJson
      ▼
JS  promise resolves/rejects (or CANCELLED / NAVIGATION_RESET / error code)
```

Key invariants (details in [ipc-protocol.md](ipc-protocol.md) and
[../security/threat-model.md](../security/threat-model.md)):

- The **capability check runs before payload deserialization and before user code**, so a
  denied command never touches your DTOs or handler.
- Handlers run on **virtual threads**; the native UI thread only copies the message and
  posts responses. No user code, I/O, or blocking waits run on the UI thread.
- Responses correlate by `id` and may complete out of order; exactly one terminal result
  is delivered per invoke, even under cancellation ([ADR-006](ADR-006-async-message-passing.md)).
- On navigation or window close the nonce is invalidated, new invokes are rejected, and
  in-flight calls are cancelled after a grace period.

## Provider selection

The runtime never scans the classpath for a platform. `JDeskApplication.run()` loads
`JDeskBootstrap` via `ServiceLoader`; the runtime in turn loads exactly one
`PlatformProvider` via `ServiceLoader`. **A packaged application contains exactly one
provider.** Zero or more than one provider is a fatal startup error with a diagnostic
("Expected exactly one … provider, found N"). In development the adapter is chosen per OS
with `-PjdeskPlatform=<macos|windows|linux>` (see
[../development/quick-start.md](../development/quick-start.md)).

## Architecture principles

- **System WebView, no bundled Chromium.** Each OS renders with its own engine
  ([ADR-003](ADR-003-system-webviews.md)); the app ships no browser runtime.
- **No localhost, no HTTP listener in production.** Assets load through the custom
  `jdesk://app/` scheme via each platform's resource-interception API. Development may use
  exactly one configured `http://127.0.0.1:<port>` origin, with no silent fallback
  ([ADR-004](ADR-004-no-localhost-production.md)).
- **No Rust.** All native access is FFM from the JVM ([ADR-001](ADR-001-java25-jpms-ffm.md)).
- **Deny by default.** Every command needs an explicit capability grant or an explicit
  `@PublicDesktopCommand` classification ([spec 12](../../JDESK_CORE_FRAMEWORK_SPEC.md)).
- **Compile-time registration.** Commands and TypeScript bindings are generated by an
  annotation processor, not runtime reflection ([ADR-005](ADR-005-compile-time-registration.md)).
- **JVM distribution first.** `jlink` runtime image + `jpackage` native packages, built
  on the target OS ([ADR-007](ADR-007-jvm-distribution-first.md), [../packaging/packaging-and-signing.md](../packaging/packaging-and-signing.md)).

See [../README.md](../README.md) for the full documentation index.
