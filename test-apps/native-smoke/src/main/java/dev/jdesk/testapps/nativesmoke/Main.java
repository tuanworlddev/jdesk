package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.BinaryStream;
import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.api.WebViewSessionConfig;
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
    private static final double MAX_IPC_P95_MS = Double.parseDouble(
            System.getProperty("jdesk.smoke.maxIpcP95Ms", "150"));
    private static final double MAX_IPC_P99_MS = Double.parseDouble(
            System.getProperty("jdesk.smoke.maxIpcP99Ms", "300"));
    private static final long MAX_RSS_BYTES = Long.getLong(
            "jdesk.smoke.maxRssBytes", 805_306_368L);
    private static final long MAX_STARTUP_READY_MS = Long.getLong(
            "jdesk.smoke.maxStartupReadyMs", 15_000L);
    private static final boolean STREAM_2GB = Boolean.getBoolean("jdesk.smoke.stream2gb");
    private static final long PROCESS_KILL_HOLD_MS = Long.getLong("jdesk.smoke.processKillHoldMs", 0L);
    private static final boolean MESSAGE_DIALOG = Boolean.getBoolean("jdesk.smoke.messageDialog");
    /** Manual opt-in: shows a real save dialog / print panel (needs a driver to dismiss). */
    private static final boolean FILE_DIALOG = Boolean.getBoolean("jdesk.smoke.fileDialog");
    private static final boolean PRINT_DIALOG = Boolean.getBoolean("jdesk.smoke.printDialog");
    /**
     * Environment-dependent probes (OS secret service, automation HTTP endpoint,
     * multi-window state persistence). On by default for the native lanes; the
     * packaging lanes launch the jpackage image with {@code -Djdesk.smoke.fullProbes=false}
     * to verify the image runs the core suite without provisioning a keyring.
     */
    private static final boolean FULL_PROBES =
            Boolean.parseBoolean(System.getProperty("jdesk.smoke.fullProbes", "true"));

    // ---- DTOs (public for JSON binding) ----
    public record RunInfo(String runId, boolean stress, boolean stream2gb,
            long processKillHoldMs, String platformId, double maxIpcP95Ms,
            double maxIpcP99Ms) {
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

    /** Forwarded page-console lines captured through the JUL bridge (console probe). */
    private static final List<String> CONSOLE_LINES =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Strong reference so the configured logger is never GC-reclaimed mid-run. */
    private static final java.util.logging.Logger PAGE_CONSOLE_JUL =
            java.util.logging.Logger.getLogger("dev.jdesk.webview.console");

    public static void main(String[] args) throws Exception {
        // Opt into production console forwarding so the console-bridge probe can assert
        // that page console.error output reaches Java logging.
        System.setProperty("jdesk.console.forward", "true");
        if (FULL_PROBES) {
            // Live-drive the automation endpoint from inside the run (real loopback HTTP).
            System.setProperty("jdesk.automation", "true");
            System.setProperty("jdesk.automation.dir",
                    java.nio.file.Files.createTempDirectory("jdesk-smoke-automation").toString());
            // Window-state persistence probe writes here instead of ~/.jdesk.
            System.setProperty("jdesk.state.dir",
                    java.nio.file.Files.createTempDirectory("jdesk-smoke-state").toString());
        }
        PAGE_CONSOLE_JUL.setLevel(java.util.logging.Level.ALL);
        PAGE_CONSOLE_JUL.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                String message = record.getMessage();
                if (record.getParameters() != null && message != null && message.contains("{0")) {
                    message = java.text.MessageFormat.format(message, record.getParameters());
                }
                CONSOLE_LINES.add(String.valueOf(message));
            }
            @Override public void flush() { }
            @Override public void close() { }
        });

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
        long startupStarted = System.nanoTime();
        AtomicBoolean startupBudgetPassed = new AtomicBoolean(false);
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
                evidence, runtimeRef, deniedRan, reportRef, reportLatch, frontendEvent,
                provider.id());

        CapabilitySet capabilities = CapabilitySet.of(Set.of(
                CapabilityGrant.forAllWindows("smoke:use")));

        // App-defined asset route: /proxy/blob is deterministic bytes; /proxy/slow
        // stalls 400 ms to prove route serving never blocks the main thread.
        dev.jdesk.api.AssetRoute proxyRoute = request -> {
            if (request.path().equals("blob")) {
                return Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                        "route-payload-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/x-jdesk-probe"));
            }
            if (request.path().equals("slow")) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(dev.jdesk.api.AssetRoute.Response.of(
                        "slow-done".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "text/plain"));
            }
            return Optional.empty();
        };

        ApplicationSpec spec = new ApplicationSpec(
                "dev.jdesk.testapps.nativesmoke",
                registry,
                capabilities,
                List.of(applyOptionalPosition(WindowConfig.builder()
                        .id(MAIN_WINDOW.value())
                        .title("JDesk native smoke")
                        .size(1000, 700)
                        .entry("jdesk://app/index.html")).build()),
                List.of(new dev.jdesk.api.LifecycleListener() {
                    @Override public void onReady() {
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startupStarted);
                        evidence.putEnvironment("startup.readyMs", Long.toString(elapsedMs));
                        boolean passed = elapsedMs <= MAX_STARTUP_READY_MS;
                        startupBudgetPassed.set(passed);
                        evidence.addCase("java:startup-ready-budget", passed,
                                "readyMs=" + elapsedMs + " maxMs=" + MAX_STARTUP_READY_MS);
                    }
                }),
                Optional.empty(),
                CommandRegistry.of(new CommandDefinition("smoke.frontendPing",
                        Optional.of("smoke:use"), PingRequest.class, Optional.empty(),
                        (request, context) -> {
                            frontendEvent.set(((PingRequest) request).tag());
                            return CompletableFuture.completedFuture(null);
                        })),
                false,
                ignored -> { },
                Optional.empty(),
                Map.of("proxy", proxyRoute));

        // Customized CSP (DEFAULT + media-src): the JS probe asserts the custom policy
        // is emitted on real native scheme responses — the movie-app CDN use case.
        RuntimeOptions options = new RuntimeOptions(
                false,
                new ClasspathAssetSource(Main.class.getModule(), "web"),
                false,
                Map.of("Content-Security-Policy",
                        CspValidator.DEFAULT_CSP + "; media-src 'self'"),
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
                    orchestrate(runtime, evidence, reportLatch, reportRef, deniedRan,
                            startupBudgetPassed, verdict),
                    "smoke-orchestrator");
            orchestrator.setDaemon(true);
            orchestrator.start();
            runtime.run();
        }
        evidence.finish(verdict.get() ? 0 : 1);
        return verdict.get() ? 0 : 1;
    }

    /** Manual native check: place the main window at a known position for osascript read. */
    private static WindowConfig.Builder applyOptionalPosition(WindowConfig.Builder builder) {
        String pos = System.getProperty("jdesk.smoke.mainPosition");
        if (pos != null && pos.contains(",")) {
            String[] xy = pos.split(",");
            builder.position(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
        }
        return builder;
    }

    private static void orchestrate(
            JDeskRuntime runtime,
            EvidenceRun evidence,
            CountDownLatch reportLatch,
            AtomicReference<Report> reportRef,
            AtomicBoolean deniedRan,
            AtomicBoolean startupBudgetPassed,
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
            if (FILE_DIALOG) {
                // A driver (osascript) types a name and clicks Save; we assert the panel
                // returned that path — the real NSSavePanel round trip.
                var saved = runtime.showSaveDialog(dev.jdesk.api.FileDialog.SaveDialog.withName(
                        "JDesk save test", "jdesk-live.txt",
                        new dev.jdesk.api.FileDialog.Filter("Text", List.of("txt"))))
                        .toCompletableFuture().get(120, TimeUnit.SECONDS);
                evidence.addCase("java:file-save-dialog", saved.path().isPresent(),
                        "path=" + saved.path().orElse("(cancelled)"));
            }
            if (PRINT_DIALOG) {
                // A driver cancels the print panel; showing it (no exception) is the check.
                runtime.window(MAIN_WINDOW).orElseThrow().print()
                        .toCompletableFuture().get(120, TimeUnit.SECONDS);
                evidence.addCase("java:window-print", true, "print panel shown and dismissed");
            }
            evidence.addCase("java:denied-handler-never-ran", !deniedRan.get(),
                    "deniedRan=" + deniedRan.get());

            // Console bridge: the page emitted console.error("SMOKE-CONSOLE-PROBE ...");
            // it must arrive through the injected capture script -> dispatcher -> logger.
            boolean consoleSeen = false;
            for (int i = 0; i < 50 && !consoleSeen; i++) {
                consoleSeen = CONSOLE_LINES.stream()
                        .anyMatch(line -> line.contains("SMOKE-CONSOLE-PROBE"));
                if (!consoleSeen) {
                    Thread.sleep(100);
                }
            }
            evidence.addCase("java:console-bridge", consoleSeen,
                    consoleSeen ? "page console.error reached Java logging"
                            : "marker never arrived; captured=" + CONSOLE_LINES.size());

            // Environment-dependent probes are skipped (recorded as passing) when
            // fullProbes is off — the packaging lanes verify the image launches and
            // runs the core suite without provisioning a keyring or HTTP endpoint.
            boolean secretsPassed = true;
            boolean automationPassed = true;
            boolean windowStatePassed = true;
            boolean sessionIsolationPassed = true;
            boolean persistentSessionPassed = true;
            if (FULL_PROBES) {
            // Secret storage: real Keychain round trip (macOS) via the public API.
            secretsPassed = false;
            sessionIsolationPassed = false;
            WindowId sharedA = new WindowId("session-shared-a");
            WindowId sharedB = new WindowId("session-shared-b");
            WindowId isolated = new WindowId("session-isolated");
            try {
                dev.jdesk.api.SecretStore secrets = runtime.secrets();
                String key = "smoke-probe-" + evidence.runId();
                secrets.put(key, "wb-api-key-줄기-🔐");
                boolean roundTrip = secrets.get(key)
                        .map("wb-api-key-줄기-🔐"::equals).orElse(false);
                secrets.put(key, "rotated");
                boolean updated = secrets.get(key).map("rotated"::equals).orElse(false);
                secrets.delete(key);
                boolean gone = secrets.get(key).isEmpty();
                secretsPassed = roundTrip && updated && gone;
                evidence.addCase("java:secret-store", secretsPassed,
                        "roundTrip=" + roundTrip + " updated=" + updated + " deleted=" + gone);
            } catch (RuntimeException e) {
                evidence.addCase("java:secret-store", false, String.valueOf(e));
            }

            // Automation endpoint: real loopback HTTP against the running app.
            automationPassed = false;
            try {
                java.nio.file.Path descriptor = java.nio.file.Path.of(
                        System.getProperty("jdesk.automation.dir"),
                        "dev.jdesk.testapps.nativesmoke.json");
                String json = java.nio.file.Files.readString(descriptor);
                int port = Integer.parseInt(json.replaceAll(".*\"port\":(\\d+).*", "$1"));
                String token = json.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
                try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
                    var windowsResponse = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/windows"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    var unauthorized = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/windows"))
                            .GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    var snapshotResponse = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(
                                    "http://127.0.0.1:" + port + "/snapshot?window=main"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    byte[] png = snapshotResponse.body();
                    boolean pngMagic = png.length > 8 && (png[0] & 0xFF) == 0x89
                            && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
                    var consoleResponse = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(
                                    "http://127.0.0.1:" + port + "/console?window=main"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    boolean consoleHasMarker =
                            consoleResponse.body().contains("SMOKE-CONSOLE-PROBE");
                    automationPassed = windowsResponse.statusCode() == 200
                                    && windowsResponse.body().contains("main")
                                    && unauthorized.statusCode() == 401
                                    && snapshotResponse.statusCode() == 200 && pngMagic
                                    && consoleResponse.statusCode() == 200 && consoleHasMarker;
                    evidence.addCase("java:automation-endpoint", automationPassed,
                            "windows=" + windowsResponse.statusCode()
                                    + " unauthorized=" + unauthorized.statusCode()
                                    + " snapshotPng=" + pngMagic + " (" + png.length + " bytes)"
                                    + " consoleMarker=" + consoleHasMarker);

                    // /evaluate returns parsed JSON under `result`.
                    var evalResponse = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/evaluate"))
                            .header("Authorization", "Bearer " + token)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    "{\"window\":\"main\",\"script\":\"({a:1,b:[2,3],c:'x'})\"}"))
                            .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    boolean evalJson = evalResponse.statusCode() == 200
                            && evalResponse.body().contains("\"a\":1")
                            && evalResponse.body().contains("\"b\":[2,3]");
                    evidence.addCase("java:automation-evaluate-json", evalJson,
                            "body=" + evalResponse.body());

                    // /input synthesizes a real DOM click; assert both the endpoint's own
                    // status (200 + ok:true — catches per-engine boolean serialization)
                    // and the page-side effect.
                    var inputResponse = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/input"))
                            .header("Authorization", "Bearer " + token)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    "{\"window\":\"main\",\"action\":\"click\","
                                            + "\"selector\":\"#input-probe\"}"))
                            .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    boolean inputAck = inputResponse.statusCode() == 200
                            && inputResponse.body().contains("\"ok\":true");
                    Thread.sleep(150);
                    var clickedResponse = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/evaluate"))
                            .header("Authorization", "Bearer " + token)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    "{\"window\":\"main\",\"script\":\"!!window.__inputProbeClicked\"}"))
                            .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    boolean inputWorked = inputAck
                            && clickedResponse.body().contains("\"result\":true");
                    evidence.addCase("java:automation-input", inputWorked,
                            "inputAck=" + inputResponse.body() + " afterClick=" + clickedResponse.body());
                    automationPassed = automationPassed && evalJson && inputWorked;

                    // Earliest-error capture: a window whose module fails to load must
                    // surface the load error in /console even though no page script ran.
                    WindowId broken = new WindowId("broken-probe");
                    runtime.openWindow(WindowConfig.builder().id(broken.value())
                            .title("broken").size(400, 300)
                            .entry("jdesk://app/broken-module.html").build())
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                    Thread.sleep(800);
                    var brokenConsole = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(
                                    "http://127.0.0.1:" + port + "/console?window=broken-probe"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    runtime.closeWindow(broken).toCompletableFuture().get(10, TimeUnit.SECONDS);
                    boolean earlyError = brokenConsole.body().contains("does-not-exist-module")
                            || brokenConsole.body().contains("also-missing")
                            || brokenConsole.body().contains("Failed to load");
                    evidence.addCase("java:early-error-capture", earlyError,
                            "brokenConsole=" + brokenConsole.body());
                    automationPassed = automationPassed && earlyError;

                    // Configured window position opens a window without error. (The
                    // exact placement is asserted deterministically in the runtime unit
                    // test JDeskRuntimeTest.configuredPositionAppliesBoundsAfterShow;
                    // WKWebView's window.screenX/screenY are unreliable for a probe.)
                    WindowId placed = new WindowId("placed-probe");
                    runtime.openWindow(WindowConfig.builder().id(placed.value())
                            .title("placed").size(360, 260).position(170, 140)
                            .entry("jdesk://app/index-secondary.html").build())
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                    Thread.sleep(200);
                    String placedAlive = runtime.evaluate(placed, "'ok'")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    runtime.closeWindow(placed).toCompletableFuture().get(10, TimeUnit.SECONDS);
                    boolean positioned = placedAlive.contains("ok");
                    evidence.addCase("java:window-position-opens", positioned,
                            "positioned window opened and responsive");
                    automationPassed = automationPassed && positioned;
                }
            } catch (Exception e) {
                evidence.addCase("java:automation-endpoint", false, String.valueOf(e));
            }

            // Printing plumbing: printFile validates inputs and reaches the OS spooler.
            try {
                boolean rejectsMissing = false;
                try {
                    runtime.printFile(dev.jdesk.api.PrintJob.of("/no/such/file.pdf"))
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    rejectsMissing = String.valueOf(ex).contains("INVALID_REQUEST")
                            || String.valueOf(ex).contains("not readable")
                            || String.valueOf(ex).contains("does not exist");
                }
                // On CUPS (macOS/Linux) submit a real PDF to a bogus printer: lp rejects
                // it, proving the job reached the spooler rather than silently succeeding.
                // Windows printFile uses ShellExecute "print", which needs a registered
                // PDF handler and a default printer that headless CI may lack — so there
                // we assert only the cross-platform missing-file rejection.
                boolean cups = !System.getProperty("os.name", "").toLowerCase().contains("win");
                boolean spoolerOutcome = true;
                if (cups) {
                    java.nio.file.Path pdf = java.nio.file.Files.createTempFile("jdesk-print", ".pdf");
                    java.nio.file.Files.writeString(pdf,
                            "%PDF-1.1\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n");
                    spoolerOutcome = false;
                    try {
                        runtime.printFile(dev.jdesk.api.PrintJob.of(pdf.toString())
                                .toPrinter("jdesk-nonexistent-printer"))
                                .toCompletableFuture().get(25, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        spoolerOutcome = true; // lp rejected the unknown printer
                    }
                    java.nio.file.Files.deleteIfExists(pdf);
                }
                evidence.addCase("java:print-file-plumbing", rejectsMissing && spoolerOutcome,
                        "cups=" + cups + " rejectsMissing=" + rejectsMissing
                                + " spoolerReached=" + spoolerOutcome);
            } catch (Exception e) {
                evidence.addCase("java:print-file-plumbing", false, String.valueOf(e));
            }

            // Window min-size + remembered bounds through real native windows.
            windowStatePassed = false;
            try {
                WindowId persist = new WindowId("persist-probe");
                var opened = runtime.openWindow(WindowConfig.builder()
                        .id(persist.value()).title("persist").size(600, 500)
                        .minSize(400, 300).rememberBounds(true)
                        .entry("jdesk://app/index-secondary.html").build())
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                opened.setBounds(120, 120, 200, 150).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                Thread.sleep(300);
                // Windows setBounds/minSize act on the OUTER frame (title bar +
                // borders), so allow up to ~48px of frame delta vs. innerWidth there;
                // macOS/Linux operate on content size and match exactly.
                int frameTolerance = 48;
                int clampedWidth = Integer.parseInt(runtime.evaluate(persist,
                        "String(window.innerWidth)").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
                boolean minEnforced = clampedWidth >= 400 - frameTolerance;
                opened.setBounds(130, 130, 555, 444).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                Thread.sleep(300);
                runtime.closeWindow(persist).toCompletableFuture().get(10, TimeUnit.SECONDS);
                var reopened = runtime.openWindow(WindowConfig.builder()
                        .id(persist.value()).title("persist").size(600, 500)
                        .minSize(400, 300).rememberBounds(true)
                        .entry("jdesk://app/index-secondary.html").build())
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                Thread.sleep(300);
                int restoredWidth = Integer.parseInt(runtime.evaluate(persist,
                        "String(window.innerWidth)").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
                runtime.closeWindow(persist).toCompletableFuture().get(10, TimeUnit.SECONDS);
                windowStatePassed = minEnforced
                        && restoredWidth >= 555 - frameTolerance && restoredWidth <= 555;
                evidence.addCase("java:window-minsize-remembered-bounds", windowStatePassed,
                        "clampedWidth=" + clampedWidth + " (min 400)"
                                + " restoredWidth=" + restoredWidth
                                + " (saved 555, frame tolerance " + frameTolerance + ")");
            } catch (Exception e) {
                evidence.addCase("java:window-minsize-remembered-bounds", false,
                        String.valueOf(e));
            }

            // Real browser-storage isolation, sharing and UA override. This exercises
            // WKWebsiteDataStore / WebView2 UDF / WebKitWebContext rather than a fake store.
            try {
                String userAgent = "JDesk-Session-Probe/1.0";
                WebViewSessionConfig sharedSession = WebViewSessionConfig
                        .privateSession("smoke-shared")
                        .userAgent(userAgent).build();
                WebViewSessionConfig isolatedSession = WebViewSessionConfig
                        .privateSession("smoke-isolated").build();
                runtime.openWindow(WindowConfig.builder().id(sharedA.value())
                        .title("session shared A").entry("jdesk://app/index-secondary.html")
                        .webViewSession(sharedSession).build())
                        .toCompletableFuture().get(15, TimeUnit.SECONDS);
                runtime.openWindow(WindowConfig.builder().id(sharedB.value())
                        .title("session shared B").entry("jdesk://app/index-secondary.html")
                        .webViewSession(sharedSession).build())
                        .toCompletableFuture().get(15, TimeUnit.SECONDS);
                runtime.openWindow(WindowConfig.builder().id(isolated.value())
                        .title("session isolated").entry("jdesk://app/index-secondary.html")
                        .webViewSession(isolatedSession).build())
                        .toCompletableFuture().get(15, TimeUnit.SECONDS);
                awaitJavascriptValue(runtime, sharedA, "document.readyState", "complete",
                        Duration.ofSeconds(10));
                awaitJavascriptValue(runtime, sharedB, "document.readyState", "complete",
                        Duration.ofSeconds(10));
                awaitJavascriptValue(runtime, isolated, "document.readyState", "complete",
                        Duration.ofSeconds(10));
                String key = "jdesk-session-isolation";
                runtime.evaluate(sharedA, "localStorage.setItem('" + key
                        + "','shared-value'); 'ok'").toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                String sharedValue = runtime.evaluate(sharedB,
                        "localStorage.getItem('" + key + "')")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                String isolatedValue = runtime.evaluate(isolated,
                        "String(localStorage.getItem('" + key + "'))")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                String actualUserAgent = runtime.evaluate(sharedA, "navigator.userAgent")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                boolean passed = "shared-value".equals(sharedValue)
                        && "null".equals(isolatedValue)
                        && userAgent.equals(actualUserAgent);
                evidence.addCase("java:webview-session-isolation", passed,
                        "shared=" + sharedValue + " isolated=" + isolatedValue
                                + " ua=" + actualUserAgent);
                sessionIsolationPassed = passed;
            } catch (Exception e) {
                evidence.addCase("java:webview-session-isolation", false, String.valueOf(e));
            } finally {
                closeQuietly(runtime, sharedA);
                closeQuietly(runtime, sharedB);
                closeQuietly(runtime, isolated);
            }
            persistentSessionPassed = false;
            WindowId first = new WindowId("session-persistent-a");
            WindowId reopened = new WindowId("session-persistent-b");
            try {
                WebViewSessionConfig persistentSession = WebViewSessionConfig
                        .persistent("smoke-persistent").build();
                boolean macOs = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT).contains("mac");
                if (macOs) {
                    try {
                        runtime.openWindow(WindowConfig.builder().id(first.value())
                                .title("unsupported persistent session")
                                .entry("jdesk://app/index-secondary.html")
                                .webViewSession(persistentSession).build())
                                .toCompletableFuture().get(15, TimeUnit.SECONDS);
                        evidence.addCase("java:webview-persistent-session-contract", false,
                                "macOS accepted a named persistent jdesk:// session");
                    } catch (Exception expected) {
                        String detail = String.valueOf(expected);
                        persistentSessionPassed = detail.contains(
                                "Named persistent WebView sessions are not supported on macOS");
                        evidence.addCase("java:webview-persistent-session-contract",
                                persistentSessionPassed,
                                persistentSessionPassed
                                        ? "macOS rejected unsupported named persistence"
                                        : detail);
                    }
                } else {
                    runtime.openWindow(WindowConfig.builder().id(first.value())
                            .title("persistent session A")
                            .entry("jdesk://app/index-secondary.html")
                            .webViewSession(persistentSession).build())
                            .toCompletableFuture().get(15, TimeUnit.SECONDS);
                    awaitJavascriptValue(runtime, first, "document.readyState", "complete",
                            Duration.ofSeconds(10));
                    String write = runtime.evaluate(first,
                            "(function(){try{localStorage.setItem('jdesk-persistent-probe',"
                                    + "'persisted');return 'ok';}catch(e){return 'ERROR:'"
                                    + "+e.name+':'+e.message;}})()")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    runtime.closeWindow(first).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);
                    runtime.openWindow(WindowConfig.builder().id(reopened.value())
                            .title("persistent session B")
                            .entry("jdesk://app/index-secondary.html")
                            .webViewSession(persistentSession).build())
                            .toCompletableFuture().get(15, TimeUnit.SECONDS);
                    awaitJavascriptValue(runtime, reopened, "document.readyState", "complete",
                            Duration.ofSeconds(10));
                    String value = runtime.evaluate(reopened,
                            "localStorage.getItem('jdesk-persistent-probe')")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    persistentSessionPassed = "ok".equals(write) && "persisted".equals(value);
                    evidence.addCase("java:webview-persistent-session-contract",
                            persistentSessionPassed, "write=" + write + " value=" + value);
                }
            } catch (Exception e) {
                evidence.addCase("java:webview-persistent-session-contract", false,
                        String.valueOf(e));
            } finally {
                closeQuietly(runtime, first);
                closeQuietly(runtime, reopened);
            }
            } // end FULL_PROBES

            // Real engine snapshot of the PASS page.
            WebViewSnapshot snapshot = runtime.snapshot(MAIN_WINDOW)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            evidence.attach("screenshot.png", snapshot.png());
            PngValidator.Result png = PngValidator.validate(snapshot.png(), 200, 200);
            evidence.addCase("java:snapshot-valid", png.valid(),
                    png.detail() + " " + png.width() + "x" + png.height()
                            + " colors=" + png.distinctColors());

            // Stabilization interval, then enforce a deliberately conservative regression budget.
            Thread.sleep(STRESS ? 3000 : 500);
            long rssAfter = dev.jdesk.testkit.evidence.RssSampler.currentRssBytes();
            evidence.putEnvironment("rss.afterProbesBytes", Long.toString(rssAfter));
            evidence.addCase("java:rss-regression-budget",
                    rssAfter > 0 && rssAfter <= MAX_RSS_BYTES,
                    "rssAfterProbes=" + rssAfter + " maxBytes=" + MAX_RSS_BYTES);

            int pending = runtime.pendingInvocations();
            evidence.addCase("java:zero-pending-invocations", pending == 0,
                    "pending=" + pending);
            int windowCount = runtime.openWindowCount();
            evidence.addCase("java:single-window-open", windowCount == 1,
                    "open=" + windowCount);

            verdict.set(report.allPassed() && !deniedRan.get() && png.valid()
                    && pending == 0 && consoleSeen && secretsPassed && automationPassed
                    && windowStatePassed && startupBudgetPassed.get()
                    && sessionIsolationPassed && persistentSessionPassed
                    && rssAfter > 0 && rssAfter <= MAX_RSS_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            evidence.addCase("orchestrator", false, "interrupted");
        } catch (Exception e) {
            evidence.addCase("orchestrator", false, String.valueOf(e));
        } finally {
            runtime.closeWindow(MAIN_WINDOW);
        }
    }

    private static void closeQuietly(JDeskRuntime runtime, WindowId windowId) {
        try {
            runtime.closeWindow(windowId).toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Cleanup path: the window may already be closed or may never have opened.
        }
    }

    private static CommandRegistry buildRegistry(
            EvidenceRun evidence,
            AtomicReference<JDeskRuntime> runtimeRef,
            AtomicBoolean deniedRan,
            AtomicReference<Report> reportRef,
            CountDownLatch reportLatch,
            AtomicReference<String> frontendEvent,
            String platformId) {
        return CommandRegistry.of(
                new CommandDefinition("smoke.runInfo", Optional.of("smoke:use"), Void.class,
                        Optional.empty(), (request, context) ->
                        CompletableFuture.completedFuture(new RunInfo(evidence.runId(), STRESS,
                                STREAM_2GB, PROCESS_KILL_HOLD_MS, platformId,
                                MAX_IPC_P95_MS, MAX_IPC_P99_MS))),

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

                new CommandDefinition("smoke.failData", Optional.of("smoke:use"), Void.class,
                        Optional.empty(), (request, context) -> {
                    throw new dev.jdesk.api.JDeskException(dev.jdesk.api.ErrorCode.INVALID_REQUEST,
                            "upstream rejected",
                            Map.of("httpStatus", 429, "retryAfterSeconds", 30), null);
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
                        awaitJavascriptValue(runtime, left,
                                "window.__routeReady || ''", "ready", Duration.ofSeconds(10));
                        awaitJavascriptValue(runtime, right,
                                "window.__routeReady || ''", "ready", Duration.ofSeconds(10));
                        runtime.emitter(left).emit("route.probe", Map.of("target", "left"));
                        runtime.emitter(right).emit("route.probe", Map.of("target", "right"));
                        String leftValue = awaitJavascriptValue(runtime, left,
                                "window.__routeTarget || ''", "left", Duration.ofSeconds(5));
                        String rightValue = awaitJavascriptValue(runtime, right,
                                "window.__routeTarget || ''", "right", Duration.ofSeconds(5));
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

    private static String awaitJavascriptValue(JDeskRuntime runtime, WindowId windowId,
            String script, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String value = "";
        do {
            value = runtime.evaluate(windowId, script)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            if (expected.equals(value)) {
                return value;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return value;
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
