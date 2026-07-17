package dev.jdesk.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable HTTP cookie value managed by a WebView session. */
public record WebViewCookie(
        String name,
        String value,
        String domain,
        String path,
        Optional<Instant> expiresAt,
        boolean secure,
        boolean httpOnly) {

    public WebViewCookie {
        name = WebViewCookieKey.requireCookieName(name);
        value = requireCookieValue(value);
        domain = WebViewCookieKey.requireCookieDomain(domain);
        path = WebViewCookieKey.requireCookiePath(path);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Creates a session cookie scoped to {@code path}. */
    public static WebViewCookie session(String name, String value, String domain, String path,
            boolean secure, boolean httpOnly) {
        return new WebViewCookie(name, value, domain, path, Optional.empty(), secure, httpOnly);
    }

    public WebViewCookieKey key() {
        return new WebViewCookieKey(name, domain, path);
    }

    private static String requireCookieValue(String value) {
        String cookieValue = Objects.requireNonNull(value, "value");
        for (int i = 0; i < cookieValue.length(); i++) {
            char c = cookieValue.charAt(i);
            // RFC 6265 cookie-octet. Keeping the portable model inside this grammar
            // avoids adapter-specific parsing of whitespace, quotes and separators.
            boolean valid = c == 0x21 || (c >= 0x23 && c <= 0x2b)
                    || (c >= 0x2d && c <= 0x3a)
                    || (c >= 0x3c && c <= 0x5b)
                    || (c >= 0x5d && c <= 0x7e);
            if (!valid) {
                throw new IllegalArgumentException("Cookie value contains an invalid character");
            }
        }
        return cookieValue;
    }
}
