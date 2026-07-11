package dev.jdesk.runtime.capability;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.net.URI;
import java.util.Locale;

/**
 * Canonical origin form: lowercase scheme and host, default ports elided, no path,
 * userinfo rejected. Used for every origin comparison; raw string equality is never a
 * security decision.
 */
public final class OriginNormalizer {
    private OriginNormalizer() {
    }

    public static String normalize(String origin) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (Exception e) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Malformed origin");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Origin must be scheme://host[:port]");
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Origin must not contain a path");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || (port == 80 && scheme.equals("http"))
                || (port == 443 && scheme.equals("https"));
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
