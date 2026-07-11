package dev.jdesk.testapps.securityprobe;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.assets.ClasspathAssetSource;
import dev.jdesk.runtime.assets.CspValidator;
import dev.jdesk.runtime.boot.JDeskRuntime;
import dev.jdesk.runtime.boot.RuntimeOptions;
import dev.jdesk.runtime.ipc.EventOverflowPolicy;
import dev.jdesk.runtime.ipc.IpcLimits;
import dev.jdesk.testkit.evidence.EvidenceRun;
import dev.jdesk.testkit.evidence.PngValidator;
import dev.jdesk.webview.spi.PlatformProvider;
import dev.jdesk.webview.spi.WebViewSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real native security-probe application (spec section 17.6). Launches a real native
 * window with the real system WebView in production mode (devMode=false, strict default
 * CSP, classpath assets) and drives the eight security probes through the actual bridge:
 *
 * <ol>
 *   <li>a sandboxed iframe cannot invoke privileged commands (no nonce, no bridge world);</li>
 *   <li>a stale nonce from a previous document is rejected after navigation;</li>
 *   <li>malformed JSON executes no user code and leaves the bridge alive;</li>
 *   <li>capability denial happens before DTO deserialization;</li>
 *   <li>encoded traversal variants cannot escape the asset root;</li>
 *   <li>production errors disclose no stack traces, secrets, or local paths;</li>
 *   <li>DevTools is disabled in production configuration;</li>
 *   <li>unsafe CSP configuration is surfaced (rejected) at release validation.</li>
 * </ol>
 *
 * <p>Probes 1 and 3-6 run in the first document, which stores accumulated results in
 * {@code sessionStorage} and then navigates to a second document; probe 2 and the final
 * report run in the second document. Machine-generated evidence is written per section 18.
 * There is deliberately no dependency on any fake platform provider: with no adapter on
 * the runtime path this application fails loudly. Exit 0 is written only when every probe
 * passes and the server-side flag assertions hold.
 */
public final class Main {
    private static final WindowId MAIN_WINDOW = new WindowId("main");

    private Main() {
    }

    // ---- DTOs (public for JSON binding) ----
    public record Ack(boolean ok) {
    }

    public record Flags(boolean ranPrivileged, boolean ranDenied) {
    }

    public record EchoRequest(String text) {
    }

    public record EchoResponse(String text, boolean ok) {
    }

    /** Payload shape for the denial probe; an object-for-int would fail binding if reached. */
    public record DeniedRequest(int boom) {
    }

    public record ReportCase(String name, boolean passed, String detail) {
    }

    public record Report(List<ReportCase> cases, boolean allPassed) {
    }

    public static void main(String[] args) throws Exception {
        Path evidenceBase = Path.of(System.getProperty("jdesk.evidence.dir", "build/evidence"));
        // Category MUST be "native": these are real system-WebView security tests, and the
        // evidence verifier only enforces the anti-fake real-provider rule (spec 18) for the
        // native/package/release categories. A "security" category would silently skip it.
        EvidenceRun evidence = EvidenceRun.start(evidenceBase,
                System.getProperty("jdesk.evidence.category", "native"),
                "security-probe " + String.join(" ", args));
        int exitCode = 1;
        try {
            exitCode = run(evidence);
        } catch (Throwable t) {
            evidence.log("fatal: " + t);
            evidence.addCase("startup", false, String.valueOf(t));
            evidence.finish(70);
            throw t;
        }
        System.out.println("EVIDENCE " + evidence.directory().toAbsolutePath());
        System.exit(exitCode);
    }

    private static int run(EvidenceRun evidence) throws Exception {
        PlatformProvider provider = loadProvider();
        evidence.providerId(provider.id());
        evidence.applicationPid(ProcessHandle.current().pid());
        evidence.log("provider=" + provider.id() + " platform=" + provider.info());

        AtomicBoolean ranPrivileged = new AtomicBoolean(false);
        AtomicBoolean ranDenied = new AtomicBoolean(false);
        AtomicReference<JDeskRuntime> runtimeRef = new AtomicReference<>();
        AtomicReference<Report> reportRef = new AtomicReference<>();
        CountDownLatch reportLatch = new CountDownLatch(1);

        CommandRegistry registry = buildRegistry(ranPrivileged, ranDenied, reportRef, reportLatch);

        // Deny-by-default capability set (spec 12.1): the privileged capability is granted
        // ONLY to window "main"; "secure:never" is never granted to anyone; probe plumbing
        // uses a separate "secure:probe" capability.
        CapabilitySet capabilities = CapabilitySet.of(Set.of(
                new CapabilityGrant("secure:privileged", Set.of("main")),
                CapabilityGrant.forAllWindows("secure:probe")));

        ApplicationSpec spec = new ApplicationSpec(
                "dev.jdesk.testapps.securityprobe",
                registry,
                capabilities,
                List.of(WindowConfig.builder()
                        .id(MAIN_WINDOW.value())
                        .title("JDesk security probe")
                        .size(1000, 700)
                        .entry("jdesk://app/index.html")
                        .build()),
                List.of(),
                Optional.empty());

        // Production posture: devMode=false, strict default CSP, classpath /web assets.
        boolean devMode = false;
        RuntimeOptions options = new RuntimeOptions(
                devMode,
                new ClasspathAssetSource(Main.class.getClassLoader(), "web"),
                false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS,
                EventOverflowPolicy.REJECT,
                Duration.ofMillis(100));

        evidence.putEnvironment("rss.startupBytes",
                Long.toString(dev.jdesk.testkit.evidence.RssSampler.currentRssBytes()));
        evidence.putEnvironment("devMode", Boolean.toString(options.devMode()));

        AtomicBoolean verdict = new AtomicBoolean(false);
        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, options)) {
            runtimeRef.set(runtime);
            Thread orchestrator = new Thread(() -> orchestrate(
                    runtime, evidence, options, reportLatch, reportRef,
                    ranPrivileged, ranDenied, verdict), "security-orchestrator");
            orchestrator.setDaemon(true);
            orchestrator.start();
            runtime.run();
        }
        evidence.finish(verdict.get() ? 0 : 1);
        return verdict.get() ? 0 : 1;
    }

    private static void orchestrate(
            JDeskRuntime runtime,
            EvidenceRun evidence,
            RuntimeOptions options,
            CountDownLatch reportLatch,
            AtomicReference<Report> reportRef,
            AtomicBoolean ranPrivileged,
            AtomicBoolean ranDenied,
            AtomicBoolean verdict) {
        try {
            // Java-side probe 7: DevTools disabled in production configuration. This is
            // config-level evidence; adapters wire DevTools off from this flag. Engine-level
            // verification (no DevTools window/menu) is a documented manual check.
            boolean devtoolsOff = !options.devMode();
            evidence.addCase("java:devtools-disabled", devtoolsOff,
                    "devMode=" + options.devMode()
                            + " (config-level; engine-level check is manual, see threat-model)");

            // Java-side probe 8: unsafe CSP configuration is surfaced at release validation.
            boolean rejectedWithoutAck;
            try {
                CspValidator.validateForRelease(
                        "default-src 'self'; script-src 'unsafe-inline'", false);
                rejectedWithoutAck = false;
            } catch (RuntimeException expected) {
                rejectedWithoutAck = true;
            }
            boolean acceptedWithAck;
            try {
                CspValidator.validateForRelease(
                        "default-src 'self'; script-src 'unsafe-inline'", true);
                acceptedWithAck = true;
            } catch (RuntimeException unexpected) {
                acceptedWithAck = false;
            }
            evidence.addCase("java:unsafe-csp-surfaced", rejectedWithoutAck && acceptedWithAck,
                    "rejectedWithoutAck=" + rejectedWithoutAck
                            + " acceptedWithAck=" + acceptedWithAck);

            if (!reportLatch.await(180, TimeUnit.SECONDS)) {
                evidence.addCase("page-report", false, "no report within 180s");
                capturePageState(runtime, evidence);
                return;
            }
            Report report = reportRef.get();
            for (ReportCase reportCase : report.cases()) {
                evidence.addCase("js:" + reportCase.name(), reportCase.passed(),
                        reportCase.detail());
            }

            // Server-side flag assertions (spec 12.1): the privileged handler ran ONLY from
            // the legitimate in-page invoke; the denied handler NEVER ran.
            boolean privilegedOk = ranPrivileged.get();
            boolean deniedNeverRan = !ranDenied.get();
            evidence.addCase("java:privileged-ran-legitimately", privilegedOk,
                    "ranPrivileged=" + ranPrivileged.get());
            evidence.addCase("java:denied-handler-never-ran", deniedNeverRan,
                    "ranDenied=" + ranDenied.get());

            // Real engine snapshot of the final (PASS) page.
            WebViewSnapshot snapshot = runtime.snapshot(MAIN_WINDOW)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            evidence.attach("screenshot.png", snapshot.png());
            PngValidator.Result png = PngValidator.validate(snapshot.png(), 200, 200);
            evidence.addCase("java:snapshot-valid", png.valid(),
                    png.detail() + " " + png.width() + "x" + png.height()
                            + " colors=" + png.distinctColors());

            int pending = runtime.pendingInvocations();
            evidence.addCase("java:zero-pending-invocations", pending == 0, "pending=" + pending);
            int windowCount = runtime.openWindowCount();
            evidence.addCase("java:single-window-open", windowCount == 1, "open=" + windowCount);

            verdict.set(report.allPassed() && devtoolsOff && rejectedWithoutAck
                    && acceptedWithAck && privilegedOk && deniedNeverRan && png.valid()
                    && pending == 0 && windowCount == 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            evidence.addCase("orchestrator", false, "interrupted");
        } catch (Exception e) {
            evidence.addCase("orchestrator", false, String.valueOf(e));
        } finally {
            runtime.closeWindow(MAIN_WINDOW);
        }
    }

    private static void capturePageState(JDeskRuntime runtime, EvidenceRun evidence) {
        try {
            String state = runtime.evaluate(MAIN_WINDOW,
                    "JSON.stringify({href: location.href, title: document.title,"
                            + " bridge: !!(window.__jdesk && window.__jdesk.post),"
                            + " nonce: window.__jdesk ? String(window.__jdesk.nonce) : 'n/a',"
                            + " status: (document.getElementById('status')||{}).textContent,"
                            + " log: document.body ? document.body.innerText.slice(0, 3000) : 'no-body'})")
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            evidence.log("page state on timeout: " + state);
        } catch (Exception e) {
            evidence.log("page state evaluation failed: " + e);
        }
        try {
            WebViewSnapshot shot = runtime.snapshot(MAIN_WINDOW)
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            evidence.attach("screenshot.png", shot.png());
        } catch (Exception e) {
            evidence.log("timeout snapshot failed: " + e);
        }
    }

    private static CommandRegistry buildRegistry(
            AtomicBoolean ranPrivileged,
            AtomicBoolean ranDenied,
            AtomicReference<Report> reportRef,
            CountDownLatch reportLatch) {
        return CommandRegistry.of(
                // Granted to window "main". A successful call proves capability works; an
                // illegitimate call (forged iframe, stale nonce) must never reach here.
                new CommandDefinition("secure.privileged", Optional.of("secure:privileged"),
                        Void.class, Optional.empty(), (request, context) -> {
                    ranPrivileged.set(true);
                    return CompletableFuture.completedFuture(new Ack(true));
                }),

                // NOT granted (secure:never). The handler must never execute; capability
                // denial happens before payload deserialization.
                new CommandDefinition("secure.denied", Optional.of("secure:never"),
                        DeniedRequest.class, Optional.empty(), (request, context) -> {
                    ranDenied.set(true); // must never happen
                    return CompletableFuture.completedFuture(new Ack(true));
                }),

                // Granted, but throws with a secret/path in the message. The public error
                // must be INTERNAL_ERROR / "Command failed" with no leakage.
                new CommandDefinition("secure.leaky", Optional.of("secure:privileged"),
                        Void.class, Optional.empty(), (request, context) -> {
                    throw new RuntimeException("SECRET-/var/db/hidden-path-token-XYZZY");
                }),

                // Probe plumbing (secure:probe, all windows).
                new CommandDefinition("secure.flags", Optional.of("secure:probe"), Void.class,
                        Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(
                                new Flags(ranPrivileged.get(), ranDenied.get()))),

                new CommandDefinition("secure.resetFlags", Optional.of("secure:probe"), Void.class,
                        Optional.empty(), (request, context) -> {
                    ranPrivileged.set(false);
                    ranDenied.set(false);
                    return CompletableFuture.completedFuture(new Ack(true));
                }),

                new CommandDefinition("secure.echo", Optional.of("secure:probe"),
                        EchoRequest.class, Optional.empty(), (request, context) -> {
                    EchoRequest echo = (EchoRequest) request;
                    return CompletableFuture.completedFuture(new EchoResponse(echo.text(), true));
                }),

                new CommandDefinition("secure.report", Optional.of("secure:probe"), Report.class,
                        Optional.empty(), (request, context) -> {
                    reportRef.set((Report) request);
                    reportLatch.countDown();
                    return CompletableFuture.completedFuture(new Ack(true));
                }));
    }

    private static PlatformProvider loadProvider() {
        List<PlatformProvider> providers = ServiceLoader
                .load(PlatformProvider.class, Main.class.getClassLoader())
                .stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) {
            throw new IllegalStateException(
                    "security-probe requires exactly one real PlatformProvider, found "
                            + providers.size()
                            + ". Run with -PjdeskPlatform=<windows|macos|linux>.");
        }
        PlatformProvider provider = providers.getFirst();
        String id = provider.id().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("fake") || id.contains("mock")) {
            throw new IllegalStateException(
                    "security-probe refuses fake providers: " + provider.id());
        }
        return provider;
    }
}
