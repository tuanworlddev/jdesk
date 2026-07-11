package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.BinaryStream;
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

    private static final boolean STRESS = Boolean.getBoolean("jdesk.smoke.stress");
    private static final boolean STREAM_2GB = Boolean.getBoolean("jdesk.smoke.stream2gb");
    private static final long PROCESS_KILL_HOLD_MS = Long.getLong("jdesk.smoke.processKillHoldMs", 0L);
    private static final boolean MESSAGE_DIALOG = Boolean.getBoolean("jdesk.smoke.messageDialog");

    // ---- DTOs (public for JSON binding) ----
    public record RunInfo(String runId, boolean stress, boolean stream2gb,
            long processKillHoldMs) {
    }

    public record EchoRequest(String text, int number) {
    }

    public record EchoResponse(String text, int number, String threadName,
            boolean uiThread, boolean virtualThread) {
    }

    public enum ProbeMode { ACTIVE, PASSIVE }

    public record NestedDto(String value, List<Integer> numbers) {
    }

    public record TypeMatrix(boolean flag, int integer, long longValue, double decimal,
            String text, String nullable, List<String> list, Map<String, Integer> map,
            ProbeMode mode, NestedDto nested) {
    }

    public record SleepRequest(long millis) {
    }

    public record SleepResponse(boolean slept) {
    }

    public record PingRequest(String tag) {
    }

    public record Ack(boolean ok) {
    }
    public record EventSeen(String tag) { }

    public record DeniedRan(boolean ran) {
    }

    public record CyclesRequest(int cycles) {
    }

    public record CyclesResponse(int completed) {
    }
    public record RoutingResponse(boolean isolated, String left, String right) {
    }
    public record WindowControlsResponse(boolean alive) { }

    public record ReportCase(String name, boolean passed, String detail) {
    }

    public record Report(List<ReportCase> cases, boolean allPassed) {
    }

    public static void main(String[] args) throws Exception {
        Path evidenceBase = Path.of(System.getProperty("jdesk.evidence.dir", "build/evidence"));
        EvidenceRun evidence = EvidenceRun.start(evidenceBase,
                System.getProperty("jdesk.evidence.category", "native"),
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
        AtomicReference<String> frontendEvent = new AtomicReference<>();
        CountDownLatch reportLatch = new CountDownLatch(1);

        CommandRegistry registry = buildRegistry(
                evidence, runtimeRef, deniedRan, reportRef, reportLatch, frontendEvent);

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
                Optional.empty(),
                CommandRegistry.of(new CommandDefinition("smoke.frontendPing",
                        Optional.of("smoke:use"), PingRequest.class, Optional.empty(),
                        (request, context) -> {
                            frontendEvent.set(((PingRequest) request).tag());
                            return CompletableFuture.completedFuture(null);
                        })));

        RuntimeOptions options = new RuntimeOptions(
                false,
                new ClasspathAssetSource(Main.class.getModule(), "web"),
                false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS,
                EventOverflowPolicy.REJECT,
                Duration.ofMillis(100));

        evidence.putEnvironment("rss.startupBytes",
                Long.toString(dev.jdesk.testkit.evidence.RssSampler.currentRssBytes()));
        evidence.putEnvironment("stressMode", Boolean.toString(STRESS));

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
                return;
            }
            Report report = reportRef.get();
            runtime.diagnostics(MAIN_WINDOW).toCompletableFuture().get(5, TimeUnit.SECONDS)
                    .engineVersion().ifPresent(evidence::webViewVersion);
            for (ReportCase reportCase : report.cases()) {
                evidence.addCase("js:" + reportCase.name(), reportCase.passed(),
                        reportCase.detail());
            }
            if (MESSAGE_DIALOG) {
                var dialogResult = runtime.showMessageDialog(new dev.jdesk.api.MessageDialog(
                        "JDesk live dialog", "Native NSAlert verification",
                        dev.jdesk.api.MessageDialog.Kind.INFO, List.of("Audit PASS")))
                        .toCompletableFuture().get(120, TimeUnit.SECONDS);
                evidence.addCase("java:native-message-dialog",
                        dialogResult.buttonIndex() == 0
                                && dialogResult.buttonLabel().equals("Audit PASS"),
                        "selected=" + dialogResult.buttonLabel());
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

            // Stabilization interval, then RSS baseline (spec 17.5: record, no threshold yet).
            Thread.sleep(STRESS ? 3000 : 500);
            long rssAfter = dev.jdesk.testkit.evidence.RssSampler.currentRssBytes();
            evidence.putEnvironment("rss.afterProbesBytes", Long.toString(rssAfter));
            evidence.addCase("java:rss-baseline-recorded", true,
                    "rssAfterProbes=" + rssAfter + " bytes (baseline only, no threshold)");

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
            CountDownLatch reportLatch,
            AtomicReference<String> frontendEvent) {
        return CommandRegistry.of(
                new CommandDefinition("smoke.runInfo", Optional.of("smoke:use"), Void.class,
                        Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(new RunInfo(evidence.runId(), STRESS,
                                STREAM_2GB, PROCESS_KILL_HOLD_MS))),

                new CommandDefinition("smoke.binaryStream", Optional.of("smoke:use"), Void.class,
                        Optional.of(Duration.ofMinutes(10)), (request, context) ->
                        CompletableFuture.completedFuture(new BinaryStream(2L * 1024 * 1024 * 1024,
                                "application/octet-stream", "jdesk-2gb.bin",
                                () -> new java.io.InputStream() {
                                    long remaining = 2L * 1024 * 1024 * 1024;
                                    @Override public int read() {
                                        if (remaining-- <= 0) return -1;
                                        return 0x5a;
                                    }
                                    @Override public int read(byte[] bytes, int off, int len) {
                                        if (remaining <= 0) return -1;
                                        int count = (int) Math.min(remaining, len);
                                        java.util.Arrays.fill(bytes, off, off + count, (byte) 0x5a);
                                        remaining -= count;
                                        return count;
                                    }
                                }))),

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

                new CommandDefinition("smoke.types", Optional.of("smoke:use"), TypeMatrix.class,
                        Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(request)),

                new CommandDefinition("smoke.timeout", Optional.of("smoke:use"), SleepRequest.class,
                        Optional.of(Duration.ofMillis(100)), (request, context) -> {
                    try {
                        Thread.sleep(((SleepRequest) request).millis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture(new SleepResponse(true));
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

                new CommandDefinition("smoke.frontendEventSeen",Optional.of("smoke:use"),Void.class,
                        Optional.empty(),(request,context)->CompletableFuture.completedFuture(
                        new EventSeen(frontendEvent.get()))),

                new CommandDefinition("smoke.windowCycles", Optional.of("smoke:use"),
                        CyclesRequest.class, Optional.of(Duration.ofSeconds(170)),
                        (request, context) -> {
                    int cycles = Math.min(((CyclesRequest) request).cycles(), 100);
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

                new CommandDefinition("smoke.multiWindowRouting", Optional.of("smoke:use"),
                        Void.class, Optional.of(Duration.ofSeconds(30)), (request, context) ->
                        CompletableFuture.supplyAsync(() -> {
                    JDeskRuntime runtime = runtimeRef.get();
                    WindowId left = new WindowId("route-left");
                    WindowId right = new WindowId("route-right");
                    try {
                        runtime.openWindow(WindowConfig.builder().id(left.value()).title("left")
                                .size(320, 240).entry("jdesk://app/index-secondary.html").build())
                                .toCompletableFuture().get(10, TimeUnit.SECONDS);
                        runtime.openWindow(WindowConfig.builder().id(right.value()).title("right")
                                .size(320, 240).entry("jdesk://app/index-secondary.html").build())
                                .toCompletableFuture().get(10, TimeUnit.SECONDS);
                        Thread.sleep(300);
                        runtime.emitter(left).emit("route.probe", Map.of("target", "left"));
                        runtime.emitter(right).emit("route.probe", Map.of("target", "right"));
                        Thread.sleep(200);
                        String leftValue = runtime.evaluate(left, "window.__routeTarget || ''")
                                .toCompletableFuture().get(5, TimeUnit.SECONDS);
                        String rightValue = runtime.evaluate(right, "window.__routeTarget || ''")
                                .toCompletableFuture().get(5, TimeUnit.SECONDS);
                        return new RoutingResponse("left".equals(leftValue)
                                && "right".equals(rightValue), leftValue, rightValue);
                    } catch (Exception e) {
                        throw new IllegalStateException("multi-window routing probe failed", e);
                    } finally {
                        runtime.closeWindow(left).exceptionally(e -> null);
                        runtime.closeWindow(right).exceptionally(e -> null);
                    }
                })),

                new CommandDefinition("smoke.windowControls", Optional.of("smoke:use"),
                        Void.class, Optional.of(Duration.ofSeconds(30)), (request, context) ->
                        CompletableFuture.supplyAsync(() -> {
                    try {
                        var handle = runtimeRef.get().window(MAIN_WINDOW).orElseThrow();
                        handle.setTitle("JDesk controls live test").toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setBounds(80, 80, 900, 650).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setAlwaysOnTop(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.focus().toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.hide().toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.show().toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setMinimized(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        Thread.sleep(150);
                        handle.setMinimized(false).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setMaximized(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        Thread.sleep(150);
                        handle.setMaximized(false).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setFullscreen(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        Thread.sleep(500);
                        handle.setFullscreen(false).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setAlwaysOnTop(false).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        handle.setTitle("JDesk native smoke").toCompletableFuture().get(5, TimeUnit.SECONDS);
                        String alive = runtimeRef.get().evaluate(MAIN_WINDOW, "'alive'")
                                .toCompletableFuture().get(5, TimeUnit.SECONDS);
                        return new WindowControlsResponse("alive".equals(alive));
                    } catch (Exception e) { throw new IllegalStateException("window controls failed", e); }
                })),

                new CommandDefinition("smoke.clipboardRead", Optional.of("smoke:use"),
                        Void.class, Optional.empty(), (request, context) ->
                        runtimeRef.get().readClipboardText().thenApply(text -> new Ack(true))),

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
