# Troubleshooting

Common problems bringing up a JDesk app, and their fixes. Several of these were found
during the framework's own bring-up. See also
[../platform/prerequisites.md](../platform/prerequisites.md) and
[gradle-plugin-reference.md](gradle-plugin-reference.md).

## "Expected exactly one JDeskBootstrap/PlatformProvider … found 0"

The runtime selects the platform adapter via `ServiceLoader` and requires **exactly one**
provider — there is no fake fallback by design.

- **In development:** pass the adapter for your OS, e.g.
  `./gradlew :examples:hello-vanilla:run -PjdeskPlatform=macos` (or `windows` / `linux`).
- **Missing `JDeskBootstrap`:** ensure `jdesk-runtime` is on the module/classpath.
- **Found > 1 provider:** you have more than one `jdesk-platform-*` on the path; keep only
  the one for the target OS.

See [../architecture/overview.md](../architecture/overview.md) for provider selection.

## Windows: WebView2 loader not found

The adapter needs `WebView2Loader.dll` resolvable at launch.

- Pass it explicitly: `-PjdeskWebView2Loader=<path>` (Gradle) or
  `-Djdesk.windows.webview2loader=<path>` (JVM system property).
- Also confirm the **WebView2 Evergreen Runtime** is installed on the machine.
- `jdeskDoctor` checks the WebView2 registry entry and the optional loader property.

## macOS: "NSWindow … must be created on the main thread" / AppKit on wrong thread

AppKit must run on the process's **first** thread. The plain `java` launcher runs `main()`
on a secondary thread on macOS.

- Add **`-XstartOnFirstThread`**. The `run` and `jdeskPackage` tasks add it automatically
  for macOS; if you launch the JVM yourself, add it manually.

## Linux: "cannot open display" / no display

WebKitGTK needs a display.

- On a headless machine/CI, run under **Xvfb** (e.g. `xvfb-run ./gradlew … -PjdeskPlatform=linux`).
- If WebKitGTK starts but fails to render in a virtualized/headless environment, set
  **`WEBKIT_DISABLE_DMABUF_RENDERER=1`**.
- Ensure `libwebkit2gtk-4.1-0` is installed (see
  [../platform/prerequisites.md](../platform/prerequisites.md)).

## Seeing what the page logs (blank screen, JS crashes)

You do not need screenshots or a debugger to see page-side failures:

- **Console bridge** — in dev mode (or with `-Djdesk.console.forward=true` in any run)
  every `console.*` call, uncaught error, and unhandled promise rejection is forwarded
  to Java logging under the logger name `dev.jdesk.webview.console`. A React crash that
  whites out the window shows up there with its stack trace.
- **Automation endpoint** — launch with `-Djdesk.automation=true` and read
  `GET /console`, evaluate JS, or take a PNG snapshot over token-gated loopback HTTP.
  See [Automate and E2E-test your app](../guides/automation-and-e2e.md).
- DevTools (Safari Web Inspector / WebView2 DevTools / WebKitGTK inspector) remain
  available in dev mode for interactive debugging.

## Blank page / "Refused to execute inline script" (CSP)

The default Content-Security-Policy is **strict and blocks inline `<script>`/inline
handlers and `eval`**. This bit the framework's own smoke page during bring-up.

- **Externalize your JS and CSS** into separate files served over `jdesk://app/` (as
  `examples/hello-vanilla` does), rather than inlining them in HTML.
- Release builds *reject* a CSP that allows `unsafe-inline`/`unsafe-eval` unless you
  explicitly acknowledge it through the named build option, which then appears in the build
  report (spec 12.4). Prefer fixing the page over relaxing the policy.
- Apps that legitimately need remote content (CDN media, remote images, HTTPS APIs)
  replace the policy with `JDeskApplication.Builder.contentSecurityPolicy(...)` — see
  [Serving assets](../guides/serving-assets.md). Content Java proxies or caches should
  use an [asset route](../guides/serving-assets.md) instead of widening the CSP.

## "CAPABILITY_DENIED" from a command

Commands are deny-by-default. The capability check runs **before** deserialization and
before your handler, so a denied command never reaches your code.

- Grant the command's `@RequiresCapability("…")` value to the window in
  `src/main/resources/jdesk-capabilities.json`:

  ```json
  { "capability": "greeting:use", "windows": ["main"] }
  ```

- Confirm the window id in the grant matches the `WindowConfig.id`.
- A command with no `@RequiresCapability` and no `@PublicDesktopCommand` is a compile-time
  error, not a runtime denial.

## Command rejected after navigation / "stale nonce" / `NAVIGATION_RESET`

Each navigation session has a nonce. On navigation or window close the nonce is
invalidated, new invokes are rejected, and in-flight calls are cancelled after a grace
period; late results never reach the new document.

- The `jdesk-client` runtime handles this: in-flight calls reject with `NAVIGATION_RESET`
  and the `hello` handshake is redone lazily. Do **not** cache and reuse an old nonce
  across a navigation — re-read `window.__jdesk.nonce` (or let the client re-handshake).
- If you hand-roll the protocol (like the vanilla page), wait for the fresh `nonce`
  control message before sending `hello`/`invoke` after any navigation.

## Command times out / cancellation

- Default max command duration is 30 s. Explicit command metadata may select a positive
  timeout up to 24 hours. Long work should report
  progress via events, not block.
- The JS client can send `cancel` by request id (also on `AbortSignal`); the runtime
  interrupts the virtual thread and completes the promise with `CANCELLED`. Exactly one
  terminal result is delivered.

## Build/codegen issues

- **No `<Service>Commands` class generated:** ensure `jdesk-codegen` is on the
  `annotationProcessor` path (or the plugin's `jdeskCodegen` configuration resolves).
- **Compile error from a command:** the processor rejects unsupported types, non-public
  DTOs/methods, overloads, duplicate/grammar-violating names, missing capability
  annotations, etc. — see the exact list in the
  [jdesk-codegen README](../../modules/jdesk-codegen/README.md).

## Evidence verification fails

- **"checksum mismatch … evidence was modified after the run":** never hand-edit files
  under `build/evidence/<run-id>/`; rerun instead.
- **"category 'native' requires a real platform provider":** a native run reported a
  missing/`unknown`/`fake`/`mock` provider. Run on real hardware/CI for that OS with the
  correct `-PjdeskPlatform`. See
  [../verification/native-testing-and-evidence.md](../verification/native-testing-and-evidence.md).
