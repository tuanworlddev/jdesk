package dev.jdesk.api;

import java.util.Objects;

/** Stable identity of an HTTP cookie within a WebView session. */
public record WebViewCookieKey(String name, String domain, String path) {
    public WebViewCookieKey {
        name = requireCookieName(name);
        domain = requireCookieDomain(domain);
        path = requireCookiePath(path);
    }

    static String requireCookieName(String value) {
        String name = Objects.requireNonNull(value, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Cookie name must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Cookie name contains an invalid character");
            }
        }
        return name;
    }

    static String requireCookieDomain(String value) {
        String domain = Objects.requireNonNull(value, "domain");
        if (domain.isBlank() || domain.indexOf('/') >= 0 || domain.indexOf(':') >= 0
                || containsControlOrWhitespace(domain)) {
            throw new IllegalArgumentException("Cookie domain is invalid");
        }
        return domain;
    }

    static String requireCookiePath(String value) {
        String path = Objects.requireNonNull(value, "path");
        if (!path.startsWith("/") || containsControl(path) || path.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Cookie path must start with '/' and be valid");
        }
        return path;
    }

    private static boolean containsControlOrWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
