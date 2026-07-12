package dev.jdesk.testapps.nativesmoke;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.AssetRoute;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GAP-002 live probe: does a real system WebView deliver a page {@code fetch(POST, body)}
 * to the Java asset pipeline as raw bytes? Boots one real native window, registers an
 * upload-echo route, and drives four fetches through {@code evaluate}: a 2&nbsp;MiB POST
 * (integrity via SHA-256 + ramp pattern), a 5&nbsp;MiB POST (oversize -&gt; 413 with a
 * 4&nbsp;MiB cap), a PUT (405), and a ranged GET (206). Prints {@code PROBE-RESULT <json>}
 * plus Java-side expectations so an external driver can confirm byte-exactness independently.
 */
public final class UploadProbe {
    private static final WindowId MAIN = new WindowId("main");
    private static final int CAP = 4 * 1024 * 1024;
    private static final int POST_BYTES = 2 * 1024 * 1024;
    private static final int OVERSIZE_BYTES = 5 * 1024 * 1024;
    private static final int DOWNLOAD_BYTES = 4 * 1024 * 1024;
    private static final int RANGE_START = 1024 * 1024;
    private static final int RANGE_END = 2 * 1024 * 1024 - 1; // inclusive

    private UploadProbe() {
    }

    public static void main(String[] args) throws Exception {
        // Set the upload cap before AssetRequest.MAX_BODY_BYTES is read at class-load.
        System.setProperty("jdesk.assets.maxUploadBytes", Integer.toString(CAP));

        PlatformProvider provider = loadProvider();
        byte[] downloadBlob = ramp(DOWNLOAD_BYTES);

        AssetRoute uploadRoute = request -> {
            byte[] body = request.body();
            String out = request.method() + ":" + body.length + ":" + sha256Hex(body)
                    + ":pattern=" + matchesRamp(body);
            return Optional.of(AssetRoute.Response.of(out.getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8"));
        };
        AssetRoute downloadRoute = request ->
                Optional.of(AssetRoute.Response.of(downloadBlob, "application/octet-stream"));

        MapAssetSource assets = new MapAssetSource().put("index.html",
                ("<!doctype html><html><head><meta charset=\"utf-8\"><title>upload-probe</title>"
                        + "</head><body>upload probe</body></html>")
                        .getBytes(StandardCharsets.UTF_8));

        ApplicationSpec spec = new ApplicationSpec(
                "dev.jdesk.testapps.nativesmoke",
                CommandRegistry.of(),
                CapabilitySet.of(java.util.Set.of()),
                List.of(WindowConfig.builder()
                        .id(MAIN.value())
                        .title("JDesk upload probe")
                        .size(640, 480)
                        .entry("jdesk://app/index.html")
                        .build()),
                List.of(),
                Optional.empty(),
                CommandRegistry.of(),
                false,
                ignored -> { },
                Optional.empty(),
                Map.of("upload", uploadRoute, "download", downloadRoute));

        RuntimeOptions options = new RuntimeOptions(
                false,
                assets,
                false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS,
                EventOverflowPolicy.REJECT,
                Duration.ofMillis(100));

        AtomicReference<String> result = new AtomicReference<>("<no-result>");
        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, options)) {
            Thread orchestrator = new Thread(() -> drive(runtime, result), "upload-probe");
            orchestrator.setDaemon(true);
            orchestrator.start();
            runtime.run();
        }

        // Java-side, independently computed expectations for the external driver to check.
        String expectedPostSha = sha256Hex(ramp(POST_BYTES));
        byte[] slice = java.util.Arrays.copyOfRange(downloadBlob, RANGE_START, RANGE_END + 1);
        System.out.println("PROBE-EXPECT postSha=" + expectedPostSha
                + " postLen=" + POST_BYTES
                + " rangeLen=" + slice.length
                + " rangeFirst=" + (slice[0] & 0xFF)
                + " rangeLast=" + (slice[slice.length - 1] & 0xFF));
        System.out.println("PROBE-RESULT " + result.get());
        System.exit(0);
    }

    private static void drive(JDeskRuntime runtime, AtomicReference<String> result) {
        try {
            Thread.sleep(1500); // let the page commit and the engine settle
            runtime.evaluate(MAIN, fireScript()).toCompletableFuture().get(15, TimeUnit.SECONDS);
            String probe = "";
            for (int i = 0; i < 150; i++) {
                probe = runtime.evaluate(MAIN,
                        "window.__probe ? JSON.stringify(window.__probe) : ''")
                        .toCompletableFuture().get(15, TimeUnit.SECONDS);
                if (probe != null && !probe.isEmpty()) {
                    break;
                }
                Thread.sleep(200);
            }
            result.set(probe == null || probe.isEmpty() ? "<timeout waiting for __probe>" : probe);
        } catch (Exception e) {
            result.set("ERROR " + e);
        } finally {
            runtime.requestStop();
        }
    }

    /** Fires all four fetches; stashes a JSON summary on {@code window.__probe}. */
    private static String fireScript() {
        return """
            (function () {
              window.__probe = null;
              const mk = (n) => { const a = new Uint8Array(n);
                for (let i = 0; i < n; i++) a[i] = (i * 31 + 7) & 0xff; return a; };
              (async () => {
                const out = {};
                const post = async (path, bytes, method) => {
                  const r = await fetch('jdesk://app/' + path,
                      { method: method || 'POST', body: bytes });
                  let t = ''; try { t = await r.text(); } catch (e) { t = '<no-body:' + e + '>'; }
                  return { status: r.status, text: t, sent: bytes ? bytes.length : 0 };
                };
                out.upload = await post('upload/x', mk(%d));
                out.oversize = await post('upload/x', mk(%d));
                const put = await fetch('jdesk://app/upload/x', { method: 'PUT', body: mk(16) });
                out.put = { status: put.status };
                const rg = await fetch('jdesk://app/download/blob',
                    { headers: { Range: 'bytes=%d-%d' } });
                const buf = new Uint8Array(await rg.arrayBuffer());
                out.range = { status: rg.status, len: buf.length,
                    first: buf[0], last: buf[buf.length - 1] };
                window.__probe = out;
              })().catch((e) => { window.__probe = { error: String(e) }; });
              return 'started';
            })()
            """.formatted(POST_BYTES, OVERSIZE_BYTES, RANGE_START, RANGE_END);
    }

    private static byte[] ramp(int n) {
        byte[] a = new byte[n];
        for (int i = 0; i < n; i++) {
            a[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return a;
    }

    private static boolean matchesRamp(byte[] body) {
        for (int i = 0; i < body.length; i++) {
            if (body[i] != (byte) ((i * 31 + 7) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlatformProvider loadProvider() {
        List<PlatformProvider> providers = ServiceLoader
                .load(PlatformProvider.class, UploadProbe.class.getClassLoader())
                .stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) {
            throw new IllegalStateException("upload-probe needs exactly one real PlatformProvider, found "
                    + providers.size() + " (run with -PjdeskPlatform=macos)");
        }
        PlatformProvider provider = providers.getFirst();
        if (provider.id().toLowerCase(Locale.ROOT).contains("fake")) {
            throw new IllegalStateException("upload-probe refuses fake providers: " + provider.id());
        }
        return provider;
    }
}
