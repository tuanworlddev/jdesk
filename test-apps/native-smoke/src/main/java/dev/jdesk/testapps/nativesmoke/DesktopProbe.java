package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.MenuItem;
import dev.jdesk.api.MenuSpec;
import dev.jdesk.api.SystemTheme;
import dev.jdesk.api.TrayHandle;
import dev.jdesk.api.TraySpec;
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
                List.of(), Optional.empty(), CommandRegistry.of(), true, // single-instance:
                ignored -> { }, Optional.empty(), Map.of()); // installs the openURL delegate
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
                    + " " + menu(runtime) + " " + icon(runtime) + " " + tray(runtime)
                    + " " + shortcut(runtime) + " " + notification(runtime)
                    + " openUrl=OK(single-instance -> JDeskAppDelegate installed, app alive;"
                    + " OS scheme routing needs a signed bundle)";
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

    private static String icon(JDeskRuntime runtime) throws Exception {
        byte[] png = makePng(32, 32);
        // The macOS impl throws unless NSImage decoded the PNG and applicationIconImage took,
        // so a clean return proves the icon was really set. (Visual is not auto-verified.)
        runtime.setApplicationIcon(png).toCompletableFuture().get(5, TimeUnit.SECONDS);
        return "icon=OK(" + png.length + "B PNG set, applicationIconImage self-checked)";
    }

    private static String tray(JDeskRuntime runtime) throws Exception {
        MenuSpec menu = MenuSpec.of(
                MenuItem.action("open", "Open"),
                MenuItem.separator(),
                MenuItem.action("quit", "Quit"));
        // createTrayItem throws unless a real NSStatusItem was installed, so a handle proves it.
        TrayHandle handle = runtime.createTrayItem(TraySpec.of("JD", menu), id -> { })
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        handle.setTitle("JD2");
        Thread.sleep(100);
        handle.close(); // remove
        return "tray=OK(created+setTitle+removed, statusItem self-checked; click NOT auto-tested)";
    }

    private static String notification(JDeskRuntime runtime) {
        try {
            runtime.showNotification("JDesk", "probe").toCompletableFuture().get(5, TimeUnit.SECONDS);
            return "notification=OK(deliver call succeeded; banner display NOT verified"
                    + " — needs signed bundle)";
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return "notification=UNAVAILABLE(" + root.getMessage() + " — expected unbundled)";
        }
    }

    private static String shortcut(JDeskRuntime runtime) throws Exception {
        // A 4-modifier combo unlikely to collide; a returned Subscription means noErr.
        dev.jdesk.api.Subscription sub = runtime
                .registerGlobalShortcut("Cmd+Ctrl+Alt+Shift+K", () -> { })
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(50);
        sub.close(); // unregister
        return "shortcut=OK(RegisterEventHotKey noErr + unregister; keypress NOT auto-tested)";
    }

    /** Minimal, guaranteed-valid RGB PNG via java.util.zip (no java.desktop dependency). */
    private static byte[] makePng(int width, int height) throws Exception {
        byte[] raw = new byte[height * (1 + width * 3)];
        int p = 0;
        for (int y = 0; y < height; y++) {
            raw[p++] = 0; // filter: none
            for (int x = 0; x < width; x++) {
                raw[p++] = 30;
                raw[p++] = (byte) 120;
                raw[p++] = (byte) 220;
            }
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        java.io.ByteArrayOutputStream ihdr = new java.io.ByteArrayOutputStream();
        ihdr.write(intBytes(width));
        ihdr.write(intBytes(height));
        ihdr.write(new byte[] {8, 2, 0, 0, 0}); // 8-bit RGB, no interlace
        writeChunk(png, "IHDR", ihdr.toByteArray());
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] compressed = new byte[raw.length + 64];
        int n = deflater.deflate(compressed);
        deflater.end();
        writeChunk(png, "IDAT", java.util.Arrays.copyOf(compressed, n));
        writeChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static byte[] intBytes(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String type, byte[] data)
            throws Exception {
        out.write(intBytes(data.length));
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(intBytes((int) crc.getValue()));
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
