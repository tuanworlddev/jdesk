# JDesk Core Framework — Implementation and Verification Specification

Status: implementation-ready  
Audience: Claude Code or another autonomous coding agent  
Working name: `JDesk`  
Primary language: Java 25  
Primary build system: Gradle Kotlin DSL  

## 0. Execution contract for the coding agent

You are responsible for implementing this specification from an empty repository through real, reproducible verification. Continue through design, implementation, review, test, fix, retest, packaging, and documentation. Do not stop after scaffolding or after unit tests.

Rules:

1. Never claim a platform, feature, test, package, performance figure, or build is complete unless the corresponding command actually ran successfully and its machine-generated evidence exists.
2. Never replace a native integration test with a mock, fake WebView, mocked FFM call, mocked process, mocked screenshot, or hard-coded passing report.
3. Unit tests may use fakes, but their reports must say `unit`; they cannot satisfy any `native`, `integration`, `package`, or `release` gate.
4. A test that is skipped, disabled, assumed, unexecuted, or unavailable is not a pass.
5. If the current machine cannot test another operating system, use a real CI runner for that OS when repository access and credentials allow it. Otherwise record the platform as `UNVERIFIED`, explain the exact blocker, and do not describe the project as complete.
6. Preserve raw logs, exit codes, environment metadata, screenshots, checksums, and test reports for every native verification run.
7. Do not manually create or edit passing evidence. Evidence must be produced by test/build code from the same commit being tested.
8. Do not weaken a test, remove an assertion, suppress an error, or lower a quality gate merely to obtain green CI. Fix the implementation or document a genuine blocker.
9. Do not use Rust anywhere. Do not use JavaFX, Swing, SWT, JCEF, JxBrowser, JNA, JNI glue authored by this project, Electron, or a localhost HTTP server in production mode.
10. Framework production source must be Java. Gradle Kotlin DSL is allowed only for build logic. TypeScript/JavaScript is allowed for the frontend SDK, generated bindings, test page, and example UI.
11. Use only public, documented OS/WebView APIs. In particular, do not call private Apple selectors to mark custom schemes secure.
12. Commit in coherent milestones if Git is available. Never overwrite unrelated user changes.

The final implementation report must distinguish:

- implemented and verified locally;
- implemented and verified in real CI;
- implemented but unverified;
- not implemented;
- blocked, with evidence of the blocker.

## 1. Product objective

Build a lightweight desktop application framework with a Java application core and a web frontend. It should provide the same high-level development model as Tauri without using Rust:

- application and plugin logic in Java;
- frontend in React, Vue, Svelte, vanilla TypeScript, or any static web build;
- operating-system WebView instead of bundling Chromium;
- type-safe asynchronous commands and events between Java and JavaScript;
- deny-by-default capabilities;
- fast development workflow;
- platform-native packaging;
- clean, modular project structure;
- evidence-backed cross-platform testing.

Production applications must not require Node.js. Node.js is a development/build dependency only when the chosen frontend requires it.

## 2. Supported scope

### 2.1 Version 1 target platforms

| Platform | Architectures | Native window | WebView | Minimum target |
| --- | --- | --- | --- | --- |
| Windows | x64; ARM64 after x64 gate | Win32 | WebView2 Evergreen | Windows 10 1809 |
| macOS | Apple Silicon; Intel after Apple Silicon gate | AppKit | WKWebView | macOS 13 |
| Linux | x64; ARM64 after x64 gate | GTK 3 | WebKitGTK 4.1 | Ubuntu 22.04-compatible runtime |

The SPI must not hard-code these versions, but these are the release validation targets.

### 2.2 Version 1 features

- one or more native windows;
- system WebView creation and destruction;
- production asset protocol;
- development URL loading;
- JavaScript-to-Java commands;
- Java-to-JavaScript events;
- cancellation and timeouts;
- compile-time command registry;
- generated TypeScript client and types;
- per-window capability checks;
- strict navigation policy;
- DevTools in development only;
- runtime diagnostics and crash notification;
- Gradle plugin and sample application;
- jlink runtime image and jpackage package;
- real native smoke tests on all supported desktop operating systems.

### 2.3 Explicit non-goals for version 1

- mobile support;
- embedded Chromium;
- browser extensions;
- a full DOM wrapper in Java;
- arbitrary remote web pages with native bridge access;
- Spring Boot in framework core;
- silent auto-update;
- GraalVM Native Image as the default distribution;
- binary IPC streaming in the first milestone;
- perfect pixel equality between operating systems.

## 3. Architectural decisions

### ADR-001: Java 25, JPMS, and FFM

Use Java 25 toolchains. All framework modules must have `module-info.java`. Native access is granted only to platform modules through `--enable-native-access`; never use `ALL-UNNAMED` in production images.

Use `java.lang.foreign` for:

- native function lookup;
- native struct layout;
- downcalls;
- upcall callbacks;
- deterministic native memory lifetime.

Do not use `sun.misc.Unsafe`.

### ADR-002: Gradle-first, Maven-compatible publication

Develop the framework as a Gradle multi-project build using Kotlin DSL and a checked-in Gradle Wrapper. Reasons:

- task graph suits Java compilation, frontend builds, code generation, native smoke tests, jlink, and jpackage;
- Java toolchains make the JDK reproducible;
- variant-aware dependencies can select OS/architecture artifacts;
- TestKit can execute real consumer builds;
- configuration/build cache can keep the developer loop fast.

Publish normal Maven artifacts, Gradle module metadata, sources, Javadocs, and a BOM. Maven consumers must be able to use core runtime artifacts. A Maven packaging plugin is not required for version 1; document this truthfully.

### ADR-003: System WebViews

- Windows: WebView2 Win32 COM API.
- macOS: AppKit `NSWindow` plus `WKWebView`.
- Linux: GTK 3 plus WebKitGTK 4.1.

The Java API must depend only on `jdesk-webview-spi`. Platform classes must never leak through the public application API.

### ADR-004: No production localhost server

Production assets are loaded using a registered custom scheme:

`jdesk://app/<path>`

Each platform adapter implements the scheme using its documented resource interception API. Development mode may load one exact configured `http://127.0.0.1:<port>` or `http://localhost:<port>` URL.

The framework must document that WKWebView does not give arbitrary custom schemes all HTTPS secure-context behavior. Do not work around this with private APIs. Web features unavailable under the common production origin must be supplied by native plugins or documented as unsupported.

### ADR-005: Compile-time registration, minimal reflection

Commands are discovered by a Java annotation processor. It generates:

- a Java command registry;
- command metadata;
- JSON schema or equivalent structural metadata;
- TypeScript request/response types;
- a typed TypeScript command client.

No classpath scanning is allowed at runtime. Java records are the default DTO form. Runtime polymorphic deserialization and Jackson default typing must remain disabled.

### ADR-006: Asynchronous message passing

The WebView never receives direct Java objects. Communication uses versioned JSON envelopes and asynchronous message passing. Native UI threads must not execute application commands or block waiting for their completion.

### ADR-007: JVM distribution first

Default release uses a trimmed `jlink` runtime and `jpackage`. GraalVM Native Image is a later optional profile and cannot delay or compromise the JVM release.

## 4. Repository structure

Create this structure unless a technically necessary adjustment is documented in an ADR:

```text
.
├── build-logic/
├── gradle/
│   └── libs.versions.toml
├── docs/
│   ├── architecture/
│   ├── development/
│   ├── security/
│   └── verification/
├── modules/
│   ├── jdesk-api/
│   ├── jdesk-runtime/
│   ├── jdesk-webview-spi/
│   ├── jdesk-native-ffm/
│   ├── jdesk-platform-windows/
│   ├── jdesk-platform-macos/
│   ├── jdesk-platform-linux/
│   ├── jdesk-codegen/
│   ├── jdesk-gradle-plugin/
│   ├── jdesk-packager/
│   └── jdesk-testkit/
├── js/
│   └── jdesk-client/
├── examples/
│   ├── hello-vanilla/
│   └── kitchen-sink/
├── test-apps/
│   ├── native-smoke/
│   ├── security-probe/
│   └── packaging-probe/
├── scripts/
├── .github/workflows/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── LICENSE
├── README.md
└── VERIFICATION.md
```

Rules:

- `jdesk-api` has no platform dependency.
- `jdesk-runtime` has no Win32, AppKit, GTK, WebKit, or WebView2 classes.
- each platform module depends on SPI and FFM support, not another platform module;
- sample apps consume published-like artifacts, not internal implementation classes;
- test fixtures are placed in dedicated test-fixture modules and cannot enter production runtime variants.

## 5. Public Java API

The exact names may evolve, but these capabilities and semantics are mandatory.

```java
JDeskApplication.builder()
    .id("dev.jdesk.example")
    .commands(GeneratedCommands.create(appServices))
    .capabilities(Capabilities.fromResource("jdesk-capabilities.json"))
    .window(WindowConfig.builder()
        .id("main")
        .title("Example")
        .size(1100, 720)
        .entry("jdesk://app/index.html")
        .build())
    .run(args);
```

Command example:

```java
public record GreetRequest(String name) {}
public record GreetResponse(String message) {}

@DesktopCommand("greeting.greet")
@RequiresCapability("greeting:use")
public CompletionStage<GreetResponse> greet(
        GreetRequest request,
        InvocationContext context) {
    return CompletableFuture.completedFuture(
        new GreetResponse("Hello " + request.name()));
}
```

Mandatory public concepts:

- `JDeskApplication`;
- `WindowConfig` and stable `WindowId`;
- `CommandRegistry`;
- `InvocationContext`;
- `CapabilitySet` and `PermissionDecision`;
- `EventEmitter` and `Subscription`;
- `UiDispatcher`;
- `PlatformInfo`;
- structured `JDeskException` with safe public error code;
- lifecycle hooks: starting, ready, close-requested, stopping, stopped.

Public APIs must use immutable records/interfaces where practical. Do not expose native addresses, `MemorySegment`, COM pointers, Objective-C objects, GTK pointers, or implementation executors.

## 6. Native lifetime and FFM rules

This is a correctness-critical section.

### 6.1 Arena ownership

- Use short-lived `Arena.ofConfined()` for temporary arguments whose native call completes synchronously.
- Use a clearly owned `Arena.ofShared()` for upcall stubs or native memory accessed by callbacks across threads.
- Never allocate per-window or per-callback memory in `Arena.global()`.
- Close callback arenas only after native event handlers are detached and the native object can no longer call them.
- Every long-lived arena must have one owner and appear in a lifecycle test.

### 6.2 Callback pinning

Maintain a `NativeCallbackRegistry` that strongly retains:

- Java callback target;
- method handle;
- upcall stub `MemorySegment`;
- owning arena;
- platform registration token.

Unregister callbacks in reverse registration order. A callback arriving after owner closure must be rejected safely and logged; it must not dereference freed memory.

### 6.3 Handle state

All native handle wrappers follow an atomic state machine:

`NEW -> OPEN -> CLOSING -> CLOSED`

Close must be idempotent. Operations after `CLOSING` fail with a defined exception. Cleaner/finalizer-based release is not accepted as the primary mechanism.

### 6.4 Platform ownership

Windows:

- implement COM `QueryInterface`, `AddRef`, and `Release` correctly;
- wrap HRESULTs and preserve numeric code plus operation name;
- free `CoTaskMem` allocations with the matching API;
- keep COM callback vtables alive for the entire subscription lifetime.

macOS:

- balance owned Objective-C objects according to documented ownership rules;
- use an autorelease pool on native callback/background threads where needed;
- retain dynamically created delegate classes/instances and upcall implementations;
- never use private selectors.

Linux:

- pair `g_object_ref`/`g_object_unref` correctly;
- keep and disconnect GLib signal handler IDs;
- respect transfer-full and transfer-none ownership from API documentation;
- do not access a GTK/WebKit object after destruction notification.

### 6.5 ABI tests

For each platform, test sizes, alignments, offsets, pointer width, UTF encoding conversions, and representative callback signatures on every supported architecture. Hard-coded struct layouts require a comment with the native declaration and SDK version source.

## 7. UI threading and event loop

Create a common `UiDispatcher` contract:

```java
interface UiDispatcher {
    boolean isUiThread();
    void execute(Runnable action);
    <T> CompletionStage<T> submit(Callable<T> action);
    void assertUiThread();
}
```

Rules:

- window and WebView objects are created, called, and destroyed only on their native UI thread;
- native callback handlers do minimum work, copy required data, and dispatch commands away from UI;
- application commands run on virtual threads by default;
- CPU-intensive commands use an explicitly bounded CPU executor;
- no `join`, `get`, monitor wait, file I/O, network I/O, or user code on the UI thread;
- posting a response to WebView is marshalled back through `UiDispatcher`;
- thread violations throw in development/test and log plus fail safely in production.

Platform rules:

- Windows WebView2 runs on an STA thread with a Win32 message pump. Initialize and uninitialize COM on the same thread.
- macOS `NSApplication` and AppKit event handling run on the first/main thread. Package with the required JVM first-thread launcher option.
- Linux GTK and WebKitGTK run on their GLib main context; marshal calls through the main context.

Create deadlock regression tests covering command completion during window close, close requested during an outstanding command, Java event emitted during navigation, and shutdown with pending callbacks.

## 8. Platform SPI

Minimum SPI:

```java
public interface PlatformProvider {
    PlatformInfo info();
    PlatformApplication createApplication(PlatformApplicationConfig config);
}

public interface PlatformApplication extends AutoCloseable {
    UiDispatcher ui();
    PlatformWindow createWindow(NativeWindowConfig config);
    void runEventLoop();
    void requestStop();
}

public interface PlatformWindow extends AutoCloseable {
    WindowId id();
    PlatformWebView webView();
    void show();
    void hide();
    void setTitle(String title);
    void setBounds(WindowBounds bounds);
}

public interface PlatformWebView extends AutoCloseable {
    void navigate(URI uri);
    void postJson(String json);
    CompletionStage<String> evaluate(String script);
    Subscription onMessage(Consumer<String> listener);
    Subscription onNavigation(NavigationListener listener);
    CompletionStage<WebViewSnapshot> snapshot();
    WebViewDiagnostics diagnostics();
}
```

Provider selection uses JPMS `ServiceLoader`. A packaged application contains exactly one provider. Startup fails with a diagnostic if zero or more than one provider is present.

## 9. Asset protocol

### 9.1 Production behavior

Implement `jdesk://app/` without a socket or HTTP listener.

Requirements:

- normalized UTF-8 paths;
- reject `..`, encoded traversal, NUL, backslash ambiguity, absolute paths, and invalid percent encoding;
- resolve only inside the configured asset root;
- correct MIME types;
- `index.html` fallback only when explicitly enabled for SPA mode;
- immutable cache headers for hashed assets;
- no-cache for HTML entry points;
- CSP and other security headers supplied by runtime configuration;
- deterministic 404 and 500 pages without leaking filesystem paths;
- support cancellation when the WebView cancels a resource request;
- stream resources instead of reading every asset fully into heap.

Platform mapping:

- WebView2 custom scheme registration with an authority component; mark secure where the public API supports it and restrict allowed origins.
- WKWebView `WKURLSchemeHandler`.
- WebKitGTK custom URI scheme; register its security classification only through public WebKitGTK APIs.

### 9.2 Development behavior

- accept one exact configured development origin;
- probe the dev server with bounded retry and a clear timeout;
- do not silently fall back to production assets;
- expose the native bridge only after origin validation;
- disable DevTools and development origin support in release packages.

## 10. IPC protocol

### 10.1 Handshake

Before commands are accepted, frontend and runtime exchange protocol versions and feature flags.

Request:

```json
{
  "v": 1,
  "kind": "hello",
  "client": "@jdesk/client",
  "clientVersion": "0.1.0",
  "nonce": "per-navigation-session-value"
}
```

The runtime rejects unsupported versions and stale navigation nonces.

### 10.2 Invoke envelope

```json
{
  "v": 1,
  "kind": "invoke",
  "id": "01J...",
  "command": "greeting.greet",
  "payload": {"name": "Tuan"},
  "nonce": "..."
}
```

Success:

```json
{
  "v": 1,
  "kind": "result",
  "id": "01J...",
  "ok": true,
  "value": {"message": "Hello Tuan"}
}
```

Failure:

```json
{
  "v": 1,
  "kind": "result",
  "id": "01J...",
  "ok": false,
  "error": {
    "code": "CAPABILITY_DENIED",
    "message": "Command is not allowed for this window"
  }
}
```

Do not send Java class names, stack traces, native pointers, filesystem paths, SQL, secrets, or internal exception messages to frontend in production.

### 10.3 Limits

Defaults, configurable downward but not silently upward:

- maximum encoded incoming message: 1 MiB;
- maximum in-flight invocations per window: 128;
- maximum command duration: 30 seconds unless command metadata overrides it;
- maximum queued events per window: 256;
- maximum command and event name length: 128 characters;
- request IDs must be unique within a navigation session.

Over-limit requests fail deterministically and do not execute user code.

### 10.4 Cancellation and shutdown

The JS client can send `cancel` by request ID. Runtime attempts to interrupt the virtual thread and completes the JS promise with `CANCELLED`. Cancellation is best effort, but exactly one terminal result is allowed.

On navigation or window close:

- invalidate the navigation nonce;
- reject new invokes;
- cancel outstanding requests after a grace period;
- remove subscriptions;
- prevent late results from reaching the new document.

### 10.5 Ordering and backpressure

- responses correlate by ID and may complete out of order;
- events from one emitter to one window preserve enqueue order;
- define event overflow policies: reject, drop-oldest, or coalesce;
- never allow unbounded queues.

## 11. Serialization and generated bindings

Use a small `JsonCodec` SPI. The initial default may use a well-maintained Java JSON library, but configure it defensively:

- no default typing;
- no arbitrary class-name deserialization;
- reject unknown envelope fields where protocol safety benefits;
- bound nesting depth, string length, number length, and total bytes;
- stable UTF-8 behavior;
- deterministic error codes.

The annotation processor must reject at compile time:

- duplicate command names;
- unsupported generic or recursive types;
- non-public command DTOs;
- raw `Object`, `Class`, `Method`, `Throwable`, native handle, or platform types in the public command contract;
- missing capability annotation unless the command explicitly declares `@PublicDesktopCommand`;
- overloaded command methods that produce ambiguous generated names.

Generated files must be deterministic: identical inputs produce byte-identical output. Add golden tests and run generation twice to prove there is no timestamp/random ordering.

Generated TypeScript API example:

```ts
const response = await commands.greeting.greet({ name: "Tuan" });
```

The TypeScript client must handle timeout, cancellation, navigation reset, runtime error codes, and listener cleanup.

## 12. Capability and security model

### 12.1 Deny by default

Every command requires an explicit capability or explicit safe-public classification. Capability evaluation inputs include:

- window ID;
- current top-level origin;
- command name;
- configured capability set;
- navigation session;
- optional resource scope.

Evaluation occurs before payload deserialization into application DTOs and before user command code runs.

### 12.2 Navigation

- production main-frame navigation is restricted to the app origin;
- remote navigation in the application WebView is denied by default;
- external links are opened only through an explicit shell/browser capability;
- popups/new-window requests are denied unless handled by application policy;
- bridge injection is limited to approved top-level origins and isolated script worlds where the platform supports them;
- subframes receive no native authority by default.

### 12.3 File access

Do not use arbitrary frontend-provided paths as authority. File-dialog selections return opaque scoped tokens. Tokens bind to:

- canonical target;
- allowed operations;
- window/session;
- expiry;
- optional single-use behavior.

The filesystem plugin must defend against traversal, symlink escape, token replay, and time-of-check/time-of-use issues as far as the target OS APIs permit.

### 12.4 Content security policy

Provide a strict default CSP. Release builds must reject configuration containing unsafe inline/eval allowances unless the developer explicitly acknowledges them through a named build option that appears in the build report.

### 12.5 Supply chain

- dependency versions are centralized and locked;
- enable Gradle dependency verification;
- generate an SBOM for release artifacts;
- publish checksums;
- no dynamic dependency versions or unpinned Git dependencies;
- document licenses of redistributed WebView2 loader or other native artifacts.

## 13. Crash handling and diagnostics

Platform adapters must subscribe to documented WebView process failure APIs.

At minimum expose:

- platform and architecture;
- OS version;
- Java runtime version;
- WebView engine/version if available;
- process failure kind;
- current app origin, excluding sensitive query data;
- recent framework lifecycle transitions;
- correlation ID.

Do not automatically recreate repeatedly crashing WebViews forever. Use bounded recovery, e.g. one automatic recreation per window within a cooldown, then show a local error page and notify the application.

Native crash diagnostics must not contain message payloads or secrets by default.

## 14. Gradle plugin

Plugin ID: `dev.jdesk.application`.

Required extension shape:

```kotlin
jdesk {
    applicationId.set("dev.example.app")
    mainClass.set("dev.example.App")

    frontend {
        directory.set(layout.projectDirectory.dir("ui"))
        devCommand.set(listOf("npm", "run", "dev"))
        buildCommand.set(listOf("npm", "run", "build"))
        devUrl.set("http://127.0.0.1:5173")
        distDirectory.set(layout.projectDirectory.dir("ui/dist"))
    }
}
```

Required tasks:

- `jdeskDoctor` — verify JDK, OS libraries, WebView runtime, frontend tool, packaging tool, and configuration;
- `jdeskGenerateBindings`;
- `jdeskFrontendBuild`;
- `jdeskDev`;
- `jdeskRuntimeImage`;
- `jdeskPackage`;
- `jdeskNativeSmokeTest`;
- `jdeskVerifyEvidence`;

Task requirements:

- lazy configuration and configuration-cache compatibility;
- declared inputs/outputs;
- no hidden download at execution time without a declared dependency/task;
- useful errors with remediation;
- support paths containing spaces and non-ASCII characters;
- terminate child frontend/app processes on Ctrl+C and failed build;
- redact environment secrets from logs;
- functional tests through Gradle TestKit executing real isolated builds;
- run consumer tests from a repository that mimics published artifacts rather than project dependencies alone.

## 15. Developer experience and application template

Default generated project:

```text
my-app/
├── ui/
├── src/main/java/dev/example/app/
│   ├── App.java
│   ├── features/
│   ├── domain/
│   └── infrastructure/
├── src/test/java/
├── src/main/resources/
│   ├── jdesk-capabilities.json
│   └── logback.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

Do not impose Spring-style global controller/service/repository packages. Prefer feature-oriented packages. Supply two templates:

- `basic`: one Java module for small applications;
- `structured`: domain, application, infrastructure, desktop composition, and UI modules.

Developer loop acceptance:

- one command starts Java and frontend;
- frontend HMR works without restarting Java;
- Java source changes trigger a controlled application restart for version 1;
- stale processes and ports are cleaned up;
- console output clearly prefixes frontend, Java runtime, and framework messages.

## 16. Packaging

### 16.1 Runtime image

Use JPMS and `jlink` to include only required modules. Embed the exact native-access option for platform modules. The runtime image must start without a globally installed JRE.

### 16.2 jpackage

Produce platform-appropriate application images and packages:

- Windows: application image plus EXE or MSI;
- macOS: `.app` plus DMG or PKG;
- Linux: application image plus DEB; RPM may follow.

Build each package on its target OS. Cross-packaging claims are forbidden.

### 16.3 Signing

Implement configuration hooks and documentation for:

- Windows Authenticode;
- macOS Developer ID signing and notarization;
- Linux package/repository signing.

Unsigned CI packages are allowed for development verification but must be labeled `UNSIGNED`. They do not satisfy a signed release gate.

## 17. Testing strategy

### 17.1 Test categories

| Category | May use fakes? | What it proves |
| --- | --- | --- |
| Unit | Yes | Pure Java logic, state machines, protocol, security decisions |
| FFM layout | No for actual layout checks | ABI layouts and conversion on current OS/arch |
| Gradle functional | No mocked Gradle execution | Real plugin tasks in isolated consumer builds |
| Native integration | No | Actual OS window, actual system WebView, actual FFM callbacks |
| Package smoke | No | Built runtime/package launches and runs the native probe |
| Manual release | No | Installation, signing, desktop behavior on physical/VM target |

### 17.2 Unit tests

Cover at least:

- handle lifecycle and idempotent close;
- callback registry ordering;
- protocol parsing/fuzz cases;
- message limits;
- correlation and exactly-once completion;
- cancellation races;
- event backpressure;
- capability denial and allow rules;
- origin normalization;
- asset path traversal corpus;
- deterministic code generation;
- redaction;
- shutdown state machine.

Use property-based tests or generated corpora for protocol and path parsing. Run static analysis and architecture tests preventing platform dependencies in core modules.

### 17.3 Real native smoke application

`test-apps/native-smoke` must launch a real native window containing the real system WebView. It must not have a dependency on any fake platform provider.

The production asset page visibly renders a unique run ID and executes these probes through the actual bridge:

1. protocol handshake;
2. JS -> Java typed echo;
3. Java -> JS event;
4. async command on a non-UI thread;
5. cancellation of a real sleeping command;
6. unknown command rejection;
7. missing capability rejection before handler execution;
8. oversize payload rejection;
9. 100 concurrent requests with correct IDs and results;
10. navigation to a disallowed remote origin is blocked;
11. production asset 200, missing asset 404, and traversal request rejected;
12. create, close, and recreate a secondary real window repeatedly;
13. clean application shutdown with zero pending invocations and detached callbacks.

The test page displays `PASS <run-id>` only after all Java and JavaScript assertions pass.

Capture the WebView through its real snapshot/capture API. Validate that the image is decodable, has expected dimensions, is not blank, and contains non-uniform pixels. OCR is optional and must not be the sole assertion.

### 17.4 Package smoke

The package probe must:

1. build frontend production assets;
2. build a jlink runtime image;
3. build a platform application image;
4. launch the application executable from that image, not via Gradle `run`;
5. execute the native smoke protocol;
6. close normally;
7. record executable path, package/image checksum, exit code, and screenshot.

Installer creation must also run. Installer installation may be a separate privileged/manual gate when CI cannot safely install it, but the limitation must be explicit.

### 17.5 Leak and stress checks

On every primary architecture:

- run at least 25 window create/close cycles;
- run at least 10,000 small IPC round trips in a stress profile;
- verify pending request/callback/window counters return to zero;
- record process RSS before and after a forced stabilization interval;
- do not impose an arbitrary memory threshold until a baseline exists; record the baseline, then set a regression threshold in a committed follow-up ADR.

### 17.6 Security probes

Tests must prove:

- an iframe cannot invoke privileged commands by default;
- a remote main-frame origin cannot retain/use a previous bridge session;
- a stale nonce is rejected;
- malformed JSON does not execute user code;
- capability denial happens before DTO handler invocation;
- encoded traversal variants cannot escape asset root;
- production errors do not disclose stack traces or local paths;
- DevTools is disabled in release packages;
- unsafe CSP configuration is surfaced in build output.

## 18. Evidence system — anti-fake requirements

Every native or package run creates:

```text
build/evidence/<run-id>/
├── manifest.json
├── environment.json
├── results.json
├── app.log
├── stdout.log
├── stderr.log
├── screenshot.png
├── checksums.sha256
└── junit.xml
```

`manifest.json` must contain:

- schema version;
- random run ID generated at test startup;
- UTC start/end timestamps;
- Git commit and dirty state;
- OS name/version and architecture;
- JDK vendor/version;
- framework version;
- platform provider ID;
- WebView engine/version if exposed;
- exact test category;
- exact executed command;
- process IDs for launched application/WebView where available;
- exit code;
- file list and SHA-256 values;
- overall status derived from individual cases.

Rules:

- test success is written only after all assertions and snapshot validation complete;
- partial/crashed runs remain `INCOMPLETE` or `FAILED`;
- `jdeskVerifyEvidence` recomputes checksums, validates schemas, checks timestamps and commit, and fails on missing artifacts;
- a `native` result must report a real platform provider; `fake`, `headless-fake`, or `mock` provider IDs are rejected;
- CI uploads the entire evidence directory even on failure;
- final reports link to CI run/artifact identifiers when available;
- never commit generated evidence as a substitute for rerunning it.

## 19. CI matrix

Create independent jobs so one platform cannot mask another:

1. `core-unit-jdk25` on Linux;
2. `gradle-plugin-functional`;
3. `windows-x64-native`;
4. `macos-arm64-native`;
5. `linux-x64-native` under X11/Xvfb initially;
6. `package-windows-x64`;
7. `package-macos-arm64`;
8. `package-linux-x64`;
9. security/static analysis;
10. evidence verification and consolidated status.

Add secondary architecture jobs only after primary jobs pass reliably.

CI rules:

- pin action versions by commit SHA where practical;
- use Gradle dependency cache, not cached test evidence;
- install documented Linux system packages explicitly;
- never set `continue-on-error` on required gates;
- skipped native jobs make the release status incomplete;
- upload raw evidence on success and failure;
- package jobs consume artifacts from the same commit/version only.

## 20. Performance measurements

Do not advertise targets before measuring. Implement a benchmark report for:

- cold time from process start to native window shown;
- time to frontend ready handshake;
- small IPC latency p50/p95/p99;
- 100 concurrent invocation throughput;
- idle Java heap and process RSS;
- runtime image and installer size;
- frontend-only rebuild time;
- Java-change development restart time.

Run benchmarks at least five times, report raw samples, median, machine configuration, WebView version, and whether antivirus/indexing may affect results. Do not compare against Tauri/Electron unless those frameworks are benchmarked on the same machine with equivalent applications.

## 21. Quality gates

Minimum gates:

- all production modules compile with warnings treated according to a documented policy;
- no forbidden dependencies;
- unit and functional tests green;
- core line coverage >= 80% and branch coverage >= 70%, excluding generated/native descriptor boilerplate only through reviewed explicit rules;
- zero known high/critical dependency vulnerabilities or a documented time-bounded exception;
- formatter and static analysis clean;
- deterministic code generation test green;
- Gradle configuration-cache test green for required plugin tasks;
- native smoke green on Windows x64, macOS ARM64, Linux x64;
- package smoke green on those same targets;
- evidence verifier green for each native/package job;
- README quick start reproduced in a fresh directory.

Coverage does not replace native verification.

## 22. Implementation phases and mandatory gates

### Phase 0 — Repository and research lock

Deliver:

- repository structure;
- Gradle wrapper/toolchain;
- module boundaries;
- ADRs for all decisions in section 3;
- dependency verification and locks;
- CI skeleton;
- `VERIFICATION.md` status table initialized to `NOT STARTED`.

Gate:

- clean build from fresh checkout;
- no native functionality claimed.

### Phase 1 — Pure Java core

Deliver:

- lifecycle state machine;
- platform SPI;
- command/event protocol;
- limits, cancellation, backpressure;
- capability engine;
- asset resolver;
- JSON codec SPI;
- comprehensive unit/property tests.

Gate:

- quality coverage thresholds;
- protocol fuzz corpus;
- architecture boundaries enforced.

### Phase 2 — Windows vertical slice

Deliver:

- Win32 event loop/window through FFM;
- COM support through FFM;
- WebView2 creation, message bridge, scheme, snapshot, navigation, process diagnostics;
- real Windows native smoke evidence.

Gate:

- `windows-x64-native` green without fake dependencies;
- repeated lifecycle and IPC stress evidence.

Do not start by implementing every Windows feature. First make one complete vertical path: process -> window -> WebView -> production asset -> handshake -> command -> response -> snapshot -> clean shutdown.

### Phase 3 — Codegen and Gradle developer workflow

Deliver:

- annotation processor;
- generated Java registry and TypeScript client;
- Gradle application plugin;
- doctor/dev/build tasks;
- TestKit consumer builds;
- vanilla example and one structured example.

Gate:

- create a fresh external sample, generate bindings, build UI, and run the Windows vertical slice using only public APIs.

### Phase 4 — macOS adapter

Deliver:

- AppKit event loop/window through FFM;
- Objective-C runtime bindings/lifetime layer;
- WKWebView message bridge, scheme handler, navigation, snapshot, process termination handling;
- Apple Silicon evidence;
- Intel build and verification when runner is available.

Gate:

- real `macos-arm64-native` green;
- no private APIs/selectors;
- application launches from packaged `.app` image.

### Phase 5 — Linux adapter

Deliver:

- GLib/GTK 3 event loop and window through FFM;
- WebKitGTK 4.1 bridge, URI scheme, navigation, snapshot, process termination handling;
- dependency diagnostics;
- X11/Xvfb verification and documented Wayland manual check.

Gate:

- real `linux-x64-native` green;
- packaged application image launches without Gradle.

### Phase 6 — Security hardening

Deliver:

- per-window capability config;
- origin/nonce lifecycle;
- bridge isolation where supported;
- navigation and popup restrictions;
- security probe application;
- threat model documentation.

Gate:

- all section 17.6 probes pass on all primary platforms.

### Phase 7 — Packaging, documentation, and release candidate

Deliver:

- jlink and jpackage pipeline;
- signing hooks;
- SBOM/checksums;
- complete quick start/API/security/platform limitation docs;
- package smoke evidence;
- consolidated verification report.

Gate:

- every required CI job green for the same commit;
- no `UNVERIFIED` primary platform;
- fresh-project quick start reproduced;
- versioned release candidate artifacts created.

## 23. Review checklist for every phase

Before marking a phase complete, perform and document:

1. implementation review against the phase requirements;
2. native lifetime and thread review for changed native code;
3. security review for changed IPC/origin/capability code;
4. test-gap review;
5. real command execution;
6. raw evidence verification;
7. clean rebuild from a fresh worktree or equivalent isolated checkout;
8. update `VERIFICATION.md` using only generated reports.

## 24. Required documentation

- `README.md`: honest status, quick start, supported/verified matrix;
- `docs/architecture/overview.md`;
- platform FFM/lifetime ADRs;
- IPC protocol specification and compatibility policy;
- threat model and capability guide;
- platform prerequisites and limitations;
- Gradle plugin reference;
- application project structure guide;
- native testing and evidence guide;
- packaging/signing guide;
- troubleshooting guide;
- contributor instructions for adding a platform or plugin.

## 25. Versioning and compatibility

- Semantic Versioning for Java artifacts and JS client.
- IPC has an independent integer protocol version.
- generated JS client declares supported protocol range.
- add compatibility tests for previous stable protocol fixtures after first release.
- public Java binary compatibility is checked between releases.
- platform SPI is initially marked internal/incubating until two external applications validate it.

## 26. Final definition of done

The project is complete for version 1 only when all statements below are true for the same commit:

- core architecture and module boundaries are implemented;
- the framework contains no Rust and no forbidden UI/browser frameworks;
- Windows x64, macOS ARM64, and Linux x64 use real system WebViews through Java FFM;
- typed commands, events, cancellation, limits, lifecycle, and capabilities work through real WebViews;
- Gradle plugin creates and packages a consumer application;
- runtime image works without a machine-wide Java installation;
- all primary native and package smoke jobs pass;
- every pass has valid machine-generated evidence;
- security probes pass;
- stress/leak counters return to zero and baselines are recorded;
- documentation quick start works from a fresh directory;
- `VERIFICATION.md` contains no primary platform marked `UNVERIFIED`, `BLOCKED`, or `ASSUMED`;
- final report includes exact commands, commit, CI runs, package checksums, known limitations, and unresolved risks.

If any item is false, report version 1 as incomplete. Do not replace this definition with a subjective statement such as “production ready”.

## 27. Final report template

````markdown
# JDesk implementation report

Commit: <sha>
Version: <version>
Date: <UTC>

## Status
- Overall: COMPLETE | INCOMPLETE | BLOCKED
- Implemented: ...
- Not implemented: ...
- Known limitations: ...

## Real verification matrix
| OS/arch | Native smoke | Package smoke | Evidence/CI | WebView version |
| --- | --- | --- | --- | --- |

## Commands actually executed
```text
<commands>
```

## Test counts and raw reports
- Unit: ...
- Functional: ...
- Native: ...
- Security: ...
- Stress: ...

## Artifacts
| Artifact | SHA-256 | Signed? | Tested? |
| --- | --- | --- | --- |

## Performance samples
<raw and summarized measurements>

## Blockers and unverified claims
<must be explicit; write None only if supported by evidence>
````

## 28. Primary technical references

Use these primary sources while implementing; verify the specific SDK/JDK version in use rather than copying stale examples.

- OpenJDK Foreign Function & Memory API, JEP 454: https://openjdk.org/jeps/454
- OpenJDK native access restrictions, JEP 472: https://openjdk.org/jeps/472
- Oracle `jlink`: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html
- Oracle `jpackage`: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html
- WebView2 threading model: https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/threading-model
- WebView2 Win32 API: https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2
- WebView2 custom schemes: https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2customschemeregistration
- WebView2 process events: https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/process-related-events
- Apple WKWebView: https://developer.apple.com/documentation/webkit/wkwebview
- Apple WKScriptMessageHandler: https://developer.apple.com/documentation/webkit/wkscriptmessagehandler
- Apple WKURLSchemeHandler: https://developer.apple.com/documentation/webkit/wkurlschemehandler
- Apple WKContentWorld overview: https://developer.apple.com/videos/play/wwdc2020/10188/
- Apple NSApplication: https://developer.apple.com/documentation/appkit/nsapplication
- WebKitGTK WebView: https://webkitgtk.org/reference/webkit2gtk/stable/class.WebView.html
- WebKitGTK user content messaging: https://webkitgtk.org/reference/webkit2gtk/stable/method.UserContentManager.register_script_message_handler.html
- Gradle Java toolchains: https://docs.gradle.org/current/userguide/toolchains.html
- Gradle multi-project builds: https://docs.gradle.org/current/userguide/multi_project_builds.html
- Gradle TestKit: https://docs.gradle.org/current/userguide/test_kit.html
- Gradle configuration cache: https://docs.gradle.org/current/userguide/configuration_cache.html

## 29. First action for Claude Code

Before writing production code:

1. inspect the repository and existing changes;
2. create `IMPLEMENTATION_STATUS.md` with every phase/gate from this spec;
3. create the ADRs;
4. confirm installed JDK, Gradle, OS, architecture, native SDKs, and available CI access using actual commands;
5. record which platforms can be verified locally and which require CI;
6. implement Phase 0;
7. continue phase by phase, fixing failures before moving forward;
8. do not ask for confirmation for normal in-scope implementation decisions already fixed by this spec;
9. stop only for a genuine authority/credential/hardware blocker, and report the exact command, output, attempted alternatives, and smallest action required from the user.
