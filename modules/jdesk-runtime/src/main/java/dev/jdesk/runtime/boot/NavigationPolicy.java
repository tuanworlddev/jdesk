package dev.jdesk.runtime.boot;

import dev.jdesk.runtime.capability.OriginNormalizer;
import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.NavigationRequest;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;

/**
 * Strict navigation policy (spec section 12.2): production main-frame navigation is
 * restricted to the allowed origins (app origin, plus the exact dev origin in dev mode).
 * Remote main-frame navigation is denied by default. Subframe loads are allowed but
 * receive no native authority — the bridge origin check is the security boundary.
 */
public final class NavigationPolicy {
    private static final Logger LOG = System.getLogger(NavigationPolicy.class.getName());

    private final Set<String> allowedOrigins;

    public NavigationPolicy(Set<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream()
                .map(OriginNormalizer::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public NavigationDecision decide(NavigationRequest request) {
        if (!request.mainFrame()) {
            return NavigationDecision.ALLOW;
        }
        String origin;
        try {
            String uri = request.uri().toString();
            origin = OriginNormalizer.normalize(originOf(uri));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Blocked main-frame navigation to unparseable target");
            return NavigationDecision.BLOCK;
        }
        if (allowedOrigins.contains(origin)) {
            return NavigationDecision.ALLOW;
        }
        LOG.log(Level.WARNING, "Blocked main-frame navigation outside allowed origins");
        return NavigationDecision.BLOCK;
    }

    private static String originOf(String uri) {
        java.net.URI parsed = java.net.URI.create(uri);
        if (parsed.getScheme() == null || parsed.getAuthority() == null) {
            throw new IllegalArgumentException("No origin");
        }
        return parsed.getScheme() + "://" + parsed.getAuthority();
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }
}
