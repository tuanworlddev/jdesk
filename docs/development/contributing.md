# Contributing

How to extend JDesk: adding a **platform adapter** and adding a **command / plugin**. The
existing three adapters (`jdesk-platform-windows`, `-macos`, `-linux`) are your templates.
Read [../architecture/overview.md](../architecture/overview.md),
[ADR-001](../architecture/ADR-001-java25-jpms-ffm.md), and
[spec section 6 (native lifetime)](../../JDESK_CORE_FRAMEWORK_SPEC.md) first.

## Ground rules

- Follow the module boundaries (ArchUnit tests enforce them): `jdesk-api` stays Java-only
  with no native/AWT/Swing/JavaFX types; native code lives in `dev.jdesk.ffm` and the
  adapters.
- Every `VERIFIED-*` claim needs machine-generated evidence — see
  [../verification/native-testing-and-evidence.md](../verification/native-testing-and-evidence.md).
  Never fake-green; test-app mains exit non-zero rather than pretend.
- Dependencies are centralized and locked; run with configuration cache and dependency
  verification on.

## Adding a platform adapter

A new adapter is a JPMS module implementing the platform SPI and registered via
`ServiceLoader`.

### 1. Implement the SPI

Implement the interfaces in `jdesk-webview-spi`
([spec section 8](../../JDESK_CORE_FRAMEWORK_SPEC.md)):

- `PlatformProvider` — `info()` + `createApplication(config)`; a stable provider id (e.g.
  `myos-mywebview`). The id must be real: `fake`/`mock`/`headless-fake`/`unknown` are
  rejected by the evidence verifier for native categories.
- `PlatformApplication` — `ui()` (the `UiDispatcher`), `createWindow`, `runEventLoop`,
  `requestStop`.
- `PlatformWindow` — `webView()`, `show`/`hide`, `setTitle`, `setBounds`.
- `PlatformWebView` — `navigate`, `postJson`, `evaluate`, `onMessage`, `onNavigation`,
  `snapshot`, `diagnostics`.

Use the existing adapter for your closest analogue as a template (COM/STA on Windows,
ObjC/AppKit on macOS, GLib/GTK on Linux).

### 2. Register via ServiceLoader

In `module-info.java`, mirror an existing adapter:

```java
module dev.jdesk.platform.myos {
    requires dev.jdesk.webview.spi;
    requires dev.jdesk.ffm;
    provides dev.jdesk.webview.spi.PlatformProvider
            with dev.jdesk.platform.myos.MyOsPlatformProvider;
}
```

A packaged app must contain exactly one provider; zero/many is a fatal startup error.

### 3. Follow the FFM lifetime rules

Section 6 / [ADR-001](../architecture/ADR-001-java25-jpms-ffm.md) are correctness-critical:

- **Arenas:** `Arena.ofConfined()` for synchronous temporary args; a clearly-owned
  `Arena.ofShared()` for upcall stubs / memory touched by callbacks across threads; never
  per-window/per-callback allocation in `Arena.global()`. Close a callback arena only
  after its native handlers are detached.
- **Callback pinning:** keep the callback target, method handle, upcall stub segment,
  owning arena, and platform token strongly referenced in a `NativeCallbackRegistry`;
  unregister in reverse order. A callback arriving after owner closure must be rejected
  safely, not dereference freed memory.
- **Handle state machine:** `NEW → OPEN → CLOSING → CLOSED`, idempotent close, defined
  exception after `CLOSING`. Cleaner/finalizer release is not the primary mechanism.
- **Threading:** window/WebView objects are created, called, and destroyed only on their
  native UI thread; marshal responses back through `UiDispatcher`; no user code or blocking
  on the UI thread.
- **Ownership specifics:** COM `QueryInterface`/`AddRef`/`Release` (Windows); balanced
  ObjC retain/release + autorelease pools, **no private selectors** (macOS);
  `g_object_ref`/`unref` + disconnect signal ids (Linux).
- **ABI tests:** cover struct sizes/alignments/offsets, pointer width, UTF conversions, and
  representative callback signatures; hard-coded struct layouts need a comment citing the
  native declaration and SDK version.

### 4. Prove it with real evidence

Make the probe suites pass on real hardware/CI for your OS:

```bash
./gradlew :test-apps:native-smoke:run -PjdeskPlatform=myos
./gradlew :test-apps:native-smoke:run -PjdeskPlatform=myos -PjdeskStress=true   # 17.5
./gradlew :test-apps:security-probe:run -PjdeskPlatform=myos                    # 17.6
./gradlew :test-apps:native-smoke:verifyEvidence
```

All section-17.3 probes must PASS through the real bridge with a validated real snapshot,
and `EvidenceVerifier` must be green with your real provider id. Add an independent CI job
(mirroring `windows-x64-native` / `linux-x64-native`) so your platform cannot be masked by
another. Update [../../VERIFICATION.md](../../VERIFICATION.md) only from the generated
report. Document any hard-coded native layout with its SDK version.

## Adding a command or plugin

Commands are the extension unit for application/plugin behavior.

1. Write a public method annotated `@DesktopCommand("dot.separated.name")` on a public
   class. Add `@RequiresCapability("scope:action")` (or `@PublicDesktopCommand` for an
   explicitly safe command). Method shape: `name(Req, InvocationContext)`,
   `name(InvocationContext)`, or `name()`, returning `CompletionStage<Res>`. `Req`/`Res`
   are public records (or `String`/boxed; `Res` also `Void`).
2. `jdesk-codegen` generates `<Service>Commands.create(instance)` plus TypeScript
   `types.ts`/`commands.ts` at compile time. Compose multiple services with
   `JDeskCommands.combine(...)` or `CommandRegistry.of(...)`. The full type rules and
   compile-time rejections are in the
   [jdesk-codegen README](../../modules/jdesk-codegen/README.md).
3. Grant the capability to the relevant window in `jdesk-capabilities.json`.
4. Keep DTOs and handlers side-effect-honest: handlers run on virtual threads; do not block
   the UI thread; report progress via events (`EventEmitter`).

See [quick-start.md](quick-start.md) for a worked example and
[project-structure.md](project-structure.md) for where feature services belong.

## Quality gates

Run the standard checks before proposing a change — see
[quality.md](quality.md) for the full list (unit/property tests, coverage thresholds,
ArchUnit boundaries, deterministic codegen golden tests, dependency verification).
