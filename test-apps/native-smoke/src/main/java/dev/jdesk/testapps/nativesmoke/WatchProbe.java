package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.api.FileWatchHandle;
import dev.jdesk.api.FileWatchOptions;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.assets.CspValidator;
import dev.jdesk.runtime.assets.MapAssetSource;
import dev.jdesk.runtime.boot.JDeskRuntime;
import dev.jdesk.runtime.boot.RuntimeOptions;
import dev.jdesk.runtime.ipc.EventOverflowPolicy;
import dev.jdesk.runtime.ipc.IpcLimits;
import dev.jdesk.webview.spi.PlatformProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * GAP-001 live probe: exercises the real {@code ApplicationHandle.watchFiles} path
 * (manager → macOS FSEvents backend) and measures create/modify/delete latency, a Unicode
 * path, and recursive subtree detection. Coalescing window is 0 so the numbers reflect raw
 * FSEvents notification latency, not batching. Prints {@code PROBE-RESULT ...}.
 */
public final class WatchProbe {
    private static final WindowId MAIN = new WindowId("main");

    private record Received(Path path, FileWatchEvent.Kind kind, long nanos) {
    }

    private WatchProbe() {
    }

    public static void main(String[] args) throws Exception {
        PlatformProvider provider = loadProvider();

        MapAssetSource assets = new MapAssetSource().put("index.html",
                "<!doctype html><title>watch-probe</title>".getBytes(StandardCharsets.UTF_8));
        ApplicationSpec spec = new ApplicationSpec(
                "dev.jdesk.testapps.nativesmoke",
                CommandRegistry.of(),
                CapabilitySet.of(Set.of()),
                List.of(WindowConfig.builder().id(MAIN.value()).title("watch probe")
                        .size(480, 320).entry("jdesk://app/index.html").build()),
                List.of(), Optional.empty(), CommandRegistry.of(), false,
                ignored -> { }, Optional.empty(), Map.of());
        RuntimeOptions options = new RuntimeOptions(false, assets, false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS, EventOverflowPolicy.REJECT, Duration.ofMillis(100));

        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, options)) {
            Thread orchestrator = new Thread(() -> drive(runtime), "watch-probe");
            orchestrator.setDaemon(true);
            orchestrator.start();
            runtime.run();
        }
        System.out.println("CLEAN-EXIT requestStop stopped the loop");
        System.exit(0);
    }

    private static void drive(JDeskRuntime runtime) {
        String result = "<no-result>";
        FileWatchHandle handle = null;
        try {
            Path dir = Files.createTempDirectory("jdesk-watch-probe").toRealPath();
            Path sub = Files.createDirectory(dir.resolve("sub"));
            BlockingQueue<Received> events = new LinkedBlockingQueue<>();
            Consumer<List<FileWatchEvent>> listener = batch -> {
                long now = System.nanoTime();
                for (FileWatchEvent event : batch) {
                    events.add(new Received(event.path(), event.kind(), now));
                }
            };
            FileWatchOptions opts = FileWatchOptions.RECURSIVE.withCoalesceWindow(Duration.ZERO);

            // watchFiles needs the platform app started; retry until the runtime is up.
            long readyDeadline = System.nanoTime() + 20_000_000_000L;
            while (handle == null) {
                try {
                    handle = runtime.watchFiles(dir, opts, listener);
                } catch (dev.jdesk.api.JDeskException e) {
                    if (System.nanoTime() > readyDeadline) {
                        throw e;
                    }
                    Thread.sleep(200);
                }
            }
            Thread.sleep(600); // let FSEvents warm up before the first measured op

            StringBuilder out = new StringBuilder();
            out.append("active=").append(handle.isActive());
            out.append(" create=").append(measure(events, dir.resolve("probe-create.txt"),
                    () -> Files.createFile(dir.resolve("probe-create.txt"))));
            out.append(" modify=").append(measure(events, dir.resolve("probe-create.txt"),
                    () -> Files.writeString(dir.resolve("probe-create.txt"), "yy")));
            out.append(" delete=").append(measure(events, dir.resolve("probe-create.txt"),
                    () -> Files.delete(dir.resolve("probe-create.txt"))));

            Path unicode = dir.resolve("日本語-Ω-café.txt");
            out.append(" unicode=").append(measure(events, unicode,
                    () -> Files.writeString(unicode, "u")));

            Path nested = sub.resolve("nested.txt");
            out.append(" recursive=").append(measure(events, nested,
                    () -> Files.writeString(nested, "n")));

            result = out.toString();
        } catch (Throwable t) {
            result = "ERROR " + t + " | cause=" + t.getCause();
            t.printStackTrace();
        } finally {
            if (handle != null) {
                try {
                    handle.close();
                } catch (RuntimeException e) {
                    System.err.println("handle close failed: " + e);
                }
            }
            System.out.println("PROBE-RESULT " + result);
            System.out.flush();
            // Honest test of requestStop at a normal time (well after startup): if it does
            // NOT stop the loop, the watchdog halts and says so; otherwise main prints CLEAN-EXIT.
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException ignored) {
                    return;
                }
                System.err.println("STOP-WATCHDOG-FIRED requestStop did not stop the loop");
                Runtime.getRuntime().halt(3);
            }, "stop-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            runtime.requestStop();
        }
    }

    /** Runs op, waits for an event on {@code target}, returns "kind@<ms>ms" or "MISS". */
    private static String measure(BlockingQueue<Received> events, Path target, Op op)
            throws Exception {
        drain(events);
        long start = System.nanoTime();
        op.run();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Received received = events.poll(200, TimeUnit.MILLISECONDS);
            if (received == null) {
                continue;
            }
            if (received.path().getFileName().equals(target.getFileName())) {
                double ms = (received.nanos() - start) / 1_000_000.0;
                return received.kind() + "@" + String.format(Locale.ROOT, "%.1f", ms) + "ms";
            }
        }
        return "MISS";
    }

    private static void drain(BlockingQueue<Received> events) {
        events.clear();
    }

    @FunctionalInterface
    private interface Op {
        void run() throws Exception;
    }

    private static PlatformProvider loadProvider() {
        List<PlatformProvider> providers = ServiceLoader
                .load(PlatformProvider.class, WatchProbe.class.getClassLoader())
                .stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) {
            throw new IllegalStateException("watch-probe needs exactly one PlatformProvider, found "
                    + providers.size() + " (run with -PjdeskPlatform=macos)");
        }
        return providers.getFirst();
    }
}
