package dev.jdesk.runtime.boot;

import dev.jdesk.runtime.assets.AssetSource;
import dev.jdesk.runtime.assets.CspValidator;
import dev.jdesk.runtime.assets.ClasspathAssetSource;
import dev.jdesk.runtime.assets.DirectoryAssetSource;
import dev.jdesk.runtime.assets.MapAssetSource;
import dev.jdesk.runtime.ipc.EventOverflowPolicy;
import dev.jdesk.runtime.ipc.IpcLimits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime wiring options with production-safe defaults: production mode, strict CSP,
 * REJECT overflow policy, spec-default limits, DevTools off.
 *
 * @param devMode true only for development launches; enables DevTools and the dev origin
 * @param assetSource where {@code jdesk://app/} content comes from
 * @param spaFallback serve index.html for extension-less misses
 * @param securityHeaders headers added to every asset response
 * @param limits IPC limits, default {@link IpcLimits#DEFAULTS}
 * @param overflowPolicy event queue overflow policy
 * @param navigationGrace delay before outstanding requests are cancelled on navigation
 */
public record RuntimeOptions(
        boolean devMode,
        AssetSource assetSource,
        boolean spaFallback,
        Map<String, String> securityHeaders,
        IpcLimits limits,
        EventOverflowPolicy overflowPolicy,
        Duration navigationGrace) {

    public RuntimeOptions {
        Objects.requireNonNull(assetSource, "assetSource");
        securityHeaders = Map.copyOf(securityHeaders);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        Objects.requireNonNull(navigationGrace, "navigationGrace");
    }

    /** Returns a copy with {@code name} set to {@code value} in {@link #securityHeaders()}. */
    public RuntimeOptions withSecurityHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Map<String, String> headers = new HashMap<>(securityHeaders);
        headers.put(name, value);
        return new RuntimeOptions(devMode, assetSource, spaFallback, headers,
                limits, overflowPolicy, navigationGrace);
    }

    public static RuntimeOptions production(AssetSource source) {
        return new RuntimeOptions(false, source, false,
                Map.of("Content-Security-Policy", CspValidator.DEFAULT_CSP),
                IpcLimits.DEFAULTS, EventOverflowPolicy.REJECT, Duration.ofMillis(100));
    }

    /**
     * Reads launch configuration from system properties set by the launcher/Gradle
     * plugin. Asset source selection, in order: {@code jdesk.assets.dir} (a directory,
     * used in development), {@code jdesk.assets.module} (classpath resources under
     * {@code web/} in a named module), {@code jdesk.assets.classpath} (classpath
     * resources under that prefix for non-modular apps), else an empty in-memory source.
     */
    public static RuntimeOptions fromSystemProperties() {
        boolean dev = Boolean.getBoolean("jdesk.dev");
        AssetSource source = Optional.ofNullable(System.getProperty("jdesk.assets.dir"))
                .<AssetSource>map(dir -> {
                    try {
                        return new DirectoryAssetSource(Path.of(dir));
                    } catch (IOException e) {
                        throw new UncheckedIOException("Invalid jdesk.assets.dir: " + dir, e);
                    }
                })
                .orElseGet(() -> Optional.ofNullable(System.getProperty("jdesk.assets.module"))
                        .<AssetSource>map(moduleName -> ModuleLayer.boot().findModule(moduleName)
                                .<AssetSource>map(module -> new ClasspathAssetSource(module, "web"))
                                .orElseThrow(() -> new IllegalStateException(
                                        "Asset module not found: " + moduleName)))
                        .orElseGet(() -> Optional.ofNullable(System.getProperty("jdesk.assets.classpath"))
                                .<AssetSource>map(prefix -> new ClasspathAssetSource(
                                        Thread.currentThread().getContextClassLoader(), prefix))
                                .orElseGet(MapAssetSource::new)));
        RuntimeOptions base = production(source);
        return new RuntimeOptions(dev, base.assetSource(), base.spaFallback(),
                base.securityHeaders(), ipcLimitsFromProperties(),
                overflowPolicyFromProperties(), base.navigationGrace());
    }

    /**
     * IPC limits from {@code jdesk.ipc.*} system properties, defaulting to {@link
     * IpcLimits#DEFAULTS}. Values may only be <em>lowered</em> from the framework ceilings (the
     * record enforces this and fails closed on an over-limit value) — a high-throughput app tunes
     * the queue/timeout down or, for bulk data, uses {@code invokeStream}/asset-route POST rather
     * than raising the 1&nbsp;MiB envelope.
     */
    static IpcLimits ipcLimitsFromProperties() {
        IpcLimits d = IpcLimits.DEFAULTS;
        int maxMessageBytes = intProperty("jdesk.ipc.maxMessageBytes", d.maxMessageBytes());
        int maxInFlight = intProperty("jdesk.ipc.maxInFlightPerWindow", d.maxInFlightPerWindow());
        long timeoutMs = longProperty("jdesk.ipc.commandTimeoutMs",
                d.defaultCommandTimeout().toMillis());
        int maxQueued = intProperty("jdesk.ipc.maxQueuedEventsPerWindow",
                d.maxQueuedEventsPerWindow());
        try {
            return new IpcLimits(maxMessageBytes, maxInFlight, Duration.ofMillis(timeoutMs),
                    maxQueued, d.maxNameLength());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid jdesk.ipc.* limits: " + e.getMessage(), e);
        }
    }

    /** Event-queue overflow policy from {@code jdesk.ipc.overflowPolicy} (default REJECT). */
    static EventOverflowPolicy overflowPolicyFromProperties() {
        String value = System.getProperty("jdesk.ipc.overflowPolicy");
        if (value == null || value.isBlank()) {
            return EventOverflowPolicy.REJECT;
        }
        try {
            return EventOverflowPolicy.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "jdesk.ipc.overflowPolicy must be REJECT, DROP_OLDEST, or COALESCE");
        }
    }

    private static int intProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static long longProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
