package dev.jdesk.testapps.nativesmoke;

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
 * Real native smoke application (spec section 17.3). Launches a real native window with
 * the real system WebView; the production asset page runs the probe suite through the
 * actual bridge and reports back. Machine-generated evidence is written per section 18.
 * There is deliberately no dependency on any fake platform provider: with no adapter on
 * the runtime path this application fails loudly.
 */
public final class Main {
    private static final WindowId MAIN_WINDOW = new WindowId("main");

    private Main() {
    }

    // ---- DTOs (public for JSON binding) ----
    public record RunInfo(String runId) {
    }

    public record EchoRequest(String text, int number) {
    }

    public record EchoResponse(String text, int number, String threadName,
            boolean uiThread, boolean virtualThread) {
    }

    public record SleepRequest(long millis) {
    }

    public record SleepResponse(boolean slept) {
    }

    public record PingRequest(String tag) {
    }

    public record Ack(boolean ok) {
    }

    public record DeniedRan(boolean ran) {
    }

    public record CyclesRequest(int cycles) {
    }

    public record CyclesResponse(int completed) {
    }

    public record ReportCase(String name, boolean passed, String detail) {
    }

    public record Report(List<ReportCase> cases, boolean allPassed) {
    }

    public static void main(String[] args) throws Exception {
        Path evidenceBase = Path.of(System.getProperty("jdesk.evidence.dir", "build/evidence"));
        EvidenceRun evidence = EvidenceRun.start(evidenceBase, "native",
                "native-smoke " + String.join(" ", args));
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

        AtomicBoolean deniedRan = new AtomicBoolean(false);
        AtomicReference<JDeskRuntime> runtimeRef = new AtomicReference<>();
        AtomicReference<Report> reportRef = new AtomicReference<>();
        CountDownLatch reportLatch = new CountDownLatch(1);

        CommandRegistry registry = buildRegistry(
                evidence, runtimeRef, deniedRan, reportRef, reportLatch);

        CapabilitySet capabilities = CapabilitySet.of(Set.of(
                CapabilityGrant.forAllWindows("smoke:use")));

        ApplicationSpec spec = new ApplicationSpec(
                "dev.jdesk.testapps.nativesmoke",
                registry,
                capabilities,
                List.of(WindowConfig.builder()
                        .id(MAIN_WINDOW.value())
                        .title("JDesk native smoke")
                        .size(1000, 700)
                        .entry("jdesk://app/index.html")
                        .build()),
                List.of(),
                Optional.empty());

        RuntimeOptions options = new RuntimeOptions(
                false,
                new ClasspathAssetSource(Main.class.getClassLoader(), "web"),
                false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS,
                EventOverflowPolicy.REJECT,
                Duration.ofMillis(100));

        AtomicBoolean verdict = new AtomicBoolean(false);
        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, options)) {
            runtimeRef.set(runtime);
            Thread orchestrator = new Thread(() ->
                    orchestrate(runtime, evidence, reportLatch, reportRef, deniedRan, verdict),
                    "smoke-orchestrator");
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
            CountDownLatch reportLatch,
            AtomicReference<Report> reportRef,
            AtomicBoolean deniedRan,
            AtomicBoolean verdict) {
        try {
            if (!reportLatch.await(180, TimeUnit.SECONDS)) {
                evidence.addCase("page-report", false, "no report within 180s");
                return;
            }
            Report report = reportRef.get();
            for (ReportCase reportCase : report.cases()) {
                evidence.addCase("js:" + reportCase.name(), reportCase.passed(),
                        reportCase.detail());
            }
            evidence.addCase("java:denied-handler-never-ran", !deniedRan.get(),
                    "deniedRan=" + deniedRan.get());

            // Real engine snapshot of the PASS page.
            WebViewSnapshot snapshot = runtime.snapshot(MAIN_WINDOW)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            evidence.attach("screenshot.png", snapshot.png());
            PngValidator.Result png = PngValidator.validate(snapshot.png(), 200, 200);
            evidence.addCase("java:snapshot-valid", png.valid(),
                    png.detail() + " " + png.width() + "x" + png.height()
                            + " colors=" + png.distinctColors());

            // Quiescence: pending invocations and secondary windows must be zero.
            Thread.sleep(500);
            int pending = runtime.pendingInvocations();
            evidence.addCase("java:zero-pending-invocations", pending == 0,
                    "pending=" + pending);
            int windowCount = runtime.openWindowCount();
            evidence.addCase("java:single-window-open", windowCount == 1,
                    "open=" + windowCount);

            verdict.set(report.allPassed() && !deniedRan.get() && png.valid() && pending == 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            evidence.addCase("orchestrator", false, "interrupted");
        } catch (Exception e) {
            evidence.addCase("orchestrator", false, String.valueOf(e));
        } finally {
            runtime.closeWindow(MAIN_WINDOW);
        }
    }

    private static CommandRegistry buildRegistry(
            EvidenceRun evidence,
            AtomicReference<JDeskRuntime> runtimeRef,
            AtomicBoolean deniedRan,
            AtomicReference<Report> reportRef,
            CountDownLatch reportLatch) {
        return CommandRegistry.of(
                new CommandDefinition("smoke.runInfo", Optional.of("smoke:use"), Void.class,
                        Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(new RunInfo(evidence.runId()))),

                new CommandDefinition("smoke.echo", Optional.of("smoke:use"), EchoRequest.class,
                        Optional.empty(), (request, context) -> {
                    EchoRequest echo = (EchoRequest) request;
                    Thread thread = Thread.currentThread();
                    boolean uiThread = runtimeRef.get().ui() != null
                            && runtimeRef.get().ui().isUiThread();
                    return CompletableFuture.completedFuture(new EchoResponse(
                            echo.text(), echo.number(), thread.getName(),
                            uiThread, thread.isVirtual()));
                }),

                new CommandDefinition("smoke.sleep", Optional.of("smoke:use"), SleepRequest.class,
                        Optional.empty(), (request, context) -> {
                    long millis = ((SleepRequest) request).millis();
                    try {
                        Thread.sleep(Math.min(millis, 30000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException("interrupted");
                    }
                    return CompletableFuture.completedFuture(new SleepResponse(true));
                }),

                new CommandDefinition("smoke.emitPing", Optional.of("smoke:use"), PingRequest.class,
                        Optional.empty(), (request, context) -> {
                    context.events().emit("smoke.ping",
                            Map.of("tag", ((PingRequest) request).tag()));
                    return CompletableFuture.completedFuture(new Ack(true));
                }),

                new CommandDefinition("smoke.denied", Optional.of("smoke:denied"), PingRequest.class,
                        Optional.empty(), (request, context) -> {
                    deniedRan.set(true); // must never happen
                    return CompletableFuture.completedFuture(new Ack(true));
                }),

                new CommandDefinition("smoke.deniedHandlerRan", Optional.of("smoke:use"), Void.class,
                        Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(new DeniedRan(deniedRan.get()))),

                new CommandDefinition("smoke.windowCycles", Optional.of("smoke:use"),
                        CyclesRequest.class, Optional.of(Duration.ofSeconds(25)),
                        (request, context) -> {
                    int cycles = Math.min(((CyclesRequest) request).cycles(), 30);
                    JDeskRuntime runtime = runtimeRef.get();
                    return CompletableFuture.supplyAsync(() -> {
                        int completed = 0;
                        for (int i = 0; i < cycles; i++) {
                            WindowId id = new WindowId("secondary-" + i);
                            try {
                                runtime.openWindow(WindowConfig.builder()
                                        .id(id.value())
                                        .title("secondary " + i)
                                        .size(400, 300)
                                        .entry("jdesk://app/index-secondary.html")
                                        .build()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                                runtime.closeWindow(id)
                                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                                completed++;
                            } catch (Exception e) {
                                evidence.log("window cycle " + i + " failed: " + e);
                                break;
                            }
                        }
                        return new CyclesResponse(completed);
                    });
                }),

                new CommandDefinition("smoke.report", Optional.of("smoke:use"), Report.class,
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
                    "native-smoke requires exactly one real PlatformProvider, found "
                            + providers.size()
                            + ". Run with -PjdeskPlatform=<windows|macos|linux>.");
        }
        PlatformProvider provider = providers.getFirst();
        String id = provider.id().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("fake") || id.contains("mock")) {
            throw new IllegalStateException(
                    "native-smoke refuses fake providers: " + provider.id());
        }
        return provider;
    }
}
