package dev.jdesk.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-directive builder for the Content-Security-Policy, so an app can widen a single
 * directive without retyping (and accidentally weakening) the whole policy. Start from
 * {@link #defaults()} — the framework's strict default — and adjust:
 *
 * <pre>{@code
 * JDeskApplication.builder()
 *     .contentSecurityPolicy(Csp.defaults()
 *         .connectSrc("'self'", "ws://127.0.0.1:7777")   // WebSocket to the game server
 *         .imgSrc("'self'", "data:", "https://cdn.example.com"))
 *     // object-src 'none', base-uri 'none', ... stay exactly as the default set them
 *     .run(args);
 * }</pre>
 *
 * Each {@code xxxSrc(...)} replaces that directive's source list; unset directives keep
 * their default. {@link #directive(String, String...)} sets any directive by name. The
 * result is screened by the same release validator as a raw policy string.
 */
public final class Csp {

    /** Insertion-ordered so the serialized policy is stable and diff-friendly. */
    private final Map<String, String> directives = new LinkedHashMap<>();

    private Csp(Map<String, String> initial) {
        directives.putAll(initial);
    }

    /** The framework's strict default policy, as a mutable per-directive builder. */
    public static Csp defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("default-src", "'self'");
        d.put("script-src", "'self'");
        d.put("style-src", "'self'");
        d.put("img-src", "'self' data:");
        d.put("connect-src", "'self'");
        d.put("object-src", "'none'");
        d.put("base-uri", "'none'");
        d.put("frame-ancestors", "'none'");
        return new Csp(d);
    }

    /** An empty policy to build from scratch (rarely needed; prefer {@link #defaults()}). */
    public static Csp empty() {
        return new Csp(Map.of());
    }

    /**
     * Sets {@code name} to the given sources (space-joined). An empty source list removes
     * the directive. Source tokens are used verbatim — quote keywords yourself
     * ({@code "'self'"}, {@code "'none'"}).
     */
    public Csp directive(String name, String... sources) {
        String directiveName = normalizeName(name);
        if (sources.length == 0) {
            directives.remove(directiveName);
        } else {
            directives.put(directiveName, String.join(" ", sources).trim());
        }
        return this;
    }

    public Csp defaultSrc(String... sources) {
        return directive("default-src", sources);
    }

    public Csp scriptSrc(String... sources) {
        return directive("script-src", sources);
    }

    public Csp styleSrc(String... sources) {
        return directive("style-src", sources);
    }

    public Csp imgSrc(String... sources) {
        return directive("img-src", sources);
    }

    public Csp connectSrc(String... sources) {
        return directive("connect-src", sources);
    }

    public Csp mediaSrc(String... sources) {
        return directive("media-src", sources);
    }

    public Csp fontSrc(String... sources) {
        return directive("font-src", sources);
    }

    public Csp frameSrc(String... sources) {
        return directive("frame-src", sources);
    }

    /** Serializes the directives into a Content-Security-Policy header value. */
    public String build() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : directives.entrySet()) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        if (out.isEmpty()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "CSP has no directives");
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return build();
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "CSP directive name is blank");
        }
        return trimmed;
    }
}
