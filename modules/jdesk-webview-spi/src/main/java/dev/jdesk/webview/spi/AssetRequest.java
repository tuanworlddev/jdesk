package dev.jdesk.webview.spi;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * @param uri full request URI, e.g. {@code jdesk://app/index.html}
 * @param method HTTP-style method reported by the engine (GET/HEAD serve assets; POST is
 *        routed to app-defined asset routes as a non-base64 binary upload channel)
 * @param body raw request body bytes as delivered by the engine, empty for GET/HEAD.
 *        Adapters read at most {@link #MAX_BODY_BYTES}+1 so the resolver can answer 413
 *        without buffering an unbounded upload. Handed over, not copied.
 * @param headers request headers reported by the engine (may be a subset; engines are
 *        only required to forward headers that affect asset resolution, e.g. Range).
 *        Keys are normalized to lower case.
 */
public record AssetRequest(URI uri, String method, byte[] body, Map<String, String> headers) {
    private static final byte[] NO_BODY = new byte[0];

    /**
     * Maximum upload body the asset pipeline accepts, from the {@code
     * jdesk.assets.maxUploadBytes} system property (positive long), default 64 MiB.
     * Bodies larger than this are rejected with 413 rather than buffered.
     */
    public static final long MAX_BODY_BYTES = resolveMaxBodyBytes();

    public AssetRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headers, "headers");
        Map<String, String> normalized = new HashMap<>(headers.size());
        for (Map.Entry<String, String> e : headers.entrySet()) {
            normalized.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        headers = Map.copyOf(normalized);
    }

    public AssetRequest(URI uri, String method) {
        this(uri, method, NO_BODY, Map.of());
    }

    public AssetRequest(URI uri, String method, Map<String, String> headers) {
        this(uri, method, NO_BODY, headers);
    }

    /** Case-insensitive request-header lookup. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    private static long resolveMaxBodyBytes() {
        long fallback = 64L * 1024 * 1024;
        String configured = System.getProperty("jdesk.assets.maxUploadBytes");
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(configured.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
