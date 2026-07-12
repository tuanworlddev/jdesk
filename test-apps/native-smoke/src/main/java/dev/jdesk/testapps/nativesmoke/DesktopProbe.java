package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.MenuItem;
import dev.jdesk.api.MenuSpec;
import dev.jdesk.api.SystemTheme;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * GAP-004 (verifiable subset) live probe: {@code systemTheme()} cross-checked against the OS
 * setting, a binary-clipboard SHA-256 round trip, and {@code setDockBadge()}. Prints
 * {@code PROBE-RESULT ...}.
 */
public final class DesktopProbe {
    private static final WindowId MAIN = new WindowId("main");
    private static final String CLIP_TYPE = "dev.jdesk.probe.bytes";

    private DesktopProbe() {
    }

    public static void main(String[] args) throws Exception {
        PlatformProvider provider = loadProvider();
        MapAssetSource assets = new MapAssetSource().put("index.html",
                "<!doctype html><title>desktop-probe</title>".getBytes(StandardCharsets.UTF_8));
        ApplicationSpec spec = new ApplicationSpec("dev.jdesk.testapps.nativesmoke",
                CommandRegistry.of(), CapabilitySet.of(Set.of()),
                List.of(WindowConfig.builder().id(MAIN.value()).title("desktop probe")
                        .size(480, 320).entry("jdesk://app/index.html").build()),
                List.of(), Optional.empty(), CommandRegistry.of(), false,
                ignored -> { }, Optional.empty(), Map.of());
        RuntimeOptions options = new RuntimeOptions(false, assets, false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS, EventOverflowPolicy.REJECT, Duration.ofMillis(100));

        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, options)) {
            Thread orchestrator = new Thread(() -> drive(runtime), "desktop-probe");
            orchestrator.setDaemon(true);
            orchestrator.start();
            runtime.run();
        }
        System.out.println("CLEAN-EXIT");
        System.exit(0);
    }

    private static void drive(JDeskRuntime runtime) {
        String result;
        try {
            awaitReady(runtime);
            result = theme(runtime) + " " + clipboard(runtime) + " " + dockBadge(runtime)
                    + " " + menu(runtime);
        } catch (Throwable t) {
            result = "ERROR " + t + " | cause=" + t.getCause();
            t.printStackTrace();
        }
        System.out.println("PROBE-RESULT " + result);
        System.out.flush();
        runtime.requestStop();
    }

    private static String theme(JDeskRuntime runtime) throws Exception {
        SystemTheme theme = runtime.systemTheme().toCompletableFuture().get(5, TimeUnit.SECONDS);
        boolean osDark = osReportsDarkMode();
        boolean matches = (theme == SystemTheme.DARK) == osDark;
        return "theme=" + theme + "(osDark=" + osDark + ",match=" + matches + ")";
    }

    private static String clipboard(JDeskRuntime runtime) throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 37 + 11) & 0xFF);
        }
        runtime.writeClipboard(CLIP_TYPE, payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        Optional<byte[]> read = runtime.readClipboard(CLIP_TYPE)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (read.isEmpty()) {
            return "clipboard=MISS(empty)";
        }
        boolean lenOk = read.get().length == payload.length;
        boolean shaOk = sha256(read.get()).equals(sha256(payload));
        return "clipboard=" + (lenOk && shaOk ? "OK(sha=" + sha256(payload).substring(0, 12) + ")"
                : "FAIL(len=" + read.get().length + ",sha=" + shaOk + ")");
    }

    private static String dockBadge(JDeskRuntime runtime) throws Exception {
        runtime.setDockBadge("42").toCompletableFuture().get(5, TimeUnit.SECONDS);
        runtime.setDockBadge(null).toCompletableFuture().get(5, TimeUnit.SECONDS); // clear
        return "dockBadge=OK(set+clear, no throw; visual NOT auto-verified)";
    }

    private static String menu(JDeskRuntime runtime) throws Exception {
        MenuSpec spec = MenuSpec.of(
                MenuItem.submenu("App",
                        MenuItem.action("about", "About"),
                        MenuItem.separator(),
                        MenuItem.action("quit", "Quit", "CmdOrCtrl+Q")),
                MenuItem.submenu("Edit",
                        MenuItem.action("copy", "Copy", "CmdOrCtrl+C"),
                        MenuItem.action("paste", "Paste", "CmdOrCtrl+V")));
        // The macOS impl self-checks that NSApp.mainMenu really has 2 top items, so a clean
        // return proves the menu is installed. The click->listener path is NOT auto-tested.
        runtime.setApplicationMenu(spec, id -> { }).toCompletableFuture().get(5, TimeUnit.SECONDS);
        return "menuInstall=OK(2 top items, arity self-checked; click NOT auto-tested)";
    }

    private static boolean osReportsDarkMode() {
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            return out.toLowerCase().contains("dark"); // key absent (light) -> "does not exist"
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitReady(JDeskRuntime runtime) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                runtime.systemTheme().toCompletableFuture().get(2, TimeUnit.SECONDS);
                return;
            } catch (Throwable t) {
                Thread.sleep(200);
            }
        }
    }

    private static PlatformProvider loadProvider() {
        List<PlatformProvider> providers = ServiceLoader
                .load(PlatformProvider.class, DesktopProbe.class.getClassLoader())
                .stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) {
            throw new IllegalStateException("desktop-probe needs one PlatformProvider, found "
                    + providers.size());
        }
        return providers.getFirst();
    }
}
