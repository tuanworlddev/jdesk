package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.PtyHandle;
import dev.jdesk.api.PtySpec;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GAP-003 live probe: drives the real {@code ApplicationHandle.openPty} path (→ macOS
 * openpty + posix_spawnp) against a real {@code /bin/sh} and checks: a genuine controlling
 * TTY, {@code stty size} before/after a resize, a propagated exit code, and that killing the
 * session kills the whole process group (no orphaned background child). Prints
 * {@code PROBE-RESULT ...}.
 */
public final class PtyProbe {
    private static final WindowId MAIN = new WindowId("main");

    private PtyProbe() {
    }

    public static void main(String[] args) throws Exception {
        PlatformProvider provider = loadProvider();
        MapAssetSource assets = new MapAssetSource().put("index.html",
                "<!doctype html><title>pty-probe</title>".getBytes(StandardCharsets.UTF_8));
        ApplicationSpec spec = new ApplicationSpec("dev.jdesk.testapps.nativesmoke",
                CommandRegistry.of(), CapabilitySet.of(Set.of()),
                List.of(WindowConfig.builder().id(MAIN.value()).title("pty probe")
                        .size(480, 320).entry("jdesk://app/index.html").build()),
                List.of(), Optional.empty(), CommandRegistry.of(), false,
                ignored -> { }, Optional.empty(), Map.of());
        RuntimeOptions options = new RuntimeOptions(false, assets, false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS, EventOverflowPolicy.REJECT, Duration.ofMillis(100));

        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, options)) {
            Thread orchestrator = new Thread(() -> drive(runtime), "pty-probe");
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
            awaitStarted(runtime);
            result = ttyAndResize(runtime) + " " + exitCode(runtime) + " " + noOrphan(runtime);
        } catch (Throwable t) {
            result = "ERROR " + t + " | cause=" + t.getCause();
            t.printStackTrace();
        }
        System.out.println("PROBE-RESULT " + result);
        System.out.flush();
        runtime.requestStop();
    }

    /** A live PTY child with a growing, thread-safe output buffer. */
    private static final class Live {
        final PtyHandle handle;
        private final StringBuilder buffer = new StringBuilder();

        Live(JDeskRuntime runtime, List<String> argv, int columns, int rows) {
            this.handle = runtime.openPty(
                    new PtySpec(argv, Optional.empty(), Map.of(), columns, rows),
                    chunk -> {
                        synchronized (buffer) {
                            buffer.append(new String(chunk, StandardCharsets.UTF_8));
                        }
                    });
        }

        String output() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }

        OptionalInt awaitExit(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (handle.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(30);
            }
            return handle.exitCode();
        }

        String awaitLine(String prefix, long timeoutMs) throws InterruptedException {
            Pattern pattern = Pattern.compile("^" + Pattern.quote(prefix) + "(.*)$",
                    Pattern.MULTILINE);
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                Matcher m = pattern.matcher(output());
                if (m.find()) {
                    return m.group(1).trim();
                }
                Thread.sleep(30);
            }
            return null;
        }
    }

    private static String ttyAndResize(JDeskRuntime runtime) throws InterruptedException {
        // Print the tty and its size, resize mid-run, print the size again.
        Live live = new Live(runtime, List.of("/bin/sh", "-c",
                "echo T=$(tty); echo S1=$(stty size); sleep 1; echo S2=$(stty size)"), 80, 24);
        Thread.sleep(400);
        live.handle.resize(100, 40);
        live.awaitExit(8000);
        String out = live.output();
        String tty = firstGroup(out, "T=(\\S+)");
        String s1 = firstGroup(out, "S1=(\\d+ \\d+)");
        String s2 = firstGroup(out, "S2=(\\d+ \\d+)");
        boolean realTty = tty != null && tty.startsWith("/dev/");
        return "tty=" + (realTty ? tty : "NO") + " size1=[" + s1 + "] size2=[" + s2 + "]";
    }

    private static String exitCode(JDeskRuntime runtime) throws InterruptedException {
        Live live = new Live(runtime, List.of("/bin/sh", "-c", "exit 7"), 80, 24);
        OptionalInt code = live.awaitExit(8000);
        return "exit=" + (code.isPresent() ? code.getAsInt() : "MISS");
    }

    private static String noOrphan(JDeskRuntime runtime) throws InterruptedException {
        // Shell backgrounds a long sleep, reports its pid, then waits. Killing the session
        // must take the whole process group with it.
        Live live = new Live(runtime, List.of("/bin/sh", "-c",
                "sleep 300 & echo C=$!; wait"), 80, 24);
        String childPid = live.awaitLine("C=", 5000);
        if (childPid == null) {
            return "noOrphan=NO-CHILD-PID";
        }
        long pid = Long.parseLong(childPid);
        boolean aliveBefore = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        live.handle.kill(); // SIGKILL to the process group
        long deadline = System.nanoTime() + 5_000_000_000L;
        boolean gone = false;
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(50);
            } else {
                gone = true;
                break;
            }
        }
        return "noOrphan=" + (aliveBefore && gone ? "OK(child " + pid + " reaped)"
                : "FAIL(aliveBefore=" + aliveBefore + " gone=" + gone + ")");
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static void awaitStarted(JDeskRuntime runtime) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                PtyHandle probe = runtime.openPty(PtySpec.of("/usr/bin/true"), out -> { });
                probe.close();
                return;
            } catch (dev.jdesk.api.JDeskException e) {
                if (!e.getMessage().contains("not started")) {
                    return; // some other state; let the real checks surface it
                }
                Thread.sleep(200);
            }
        }
    }

    private static PlatformProvider loadProvider() {
        List<PlatformProvider> providers = ServiceLoader
                .load(PlatformProvider.class, PtyProbe.class.getClassLoader())
                .stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) {
            throw new IllegalStateException("pty-probe needs exactly one PlatformProvider, found "
                    + providers.size() + " (run with -PjdeskPlatform=macos)");
        }
        return providers.getFirst();
    }
}
