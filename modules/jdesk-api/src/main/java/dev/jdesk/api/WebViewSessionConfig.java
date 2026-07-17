package dev.jdesk.api;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable browser-session configuration shared by one or more windows.
 *
 * <p>Windows that use the same application id and session id share browser state.
 * Adapters isolate different supported session ids or reject a configuration when the
 * native engine cannot guarantee that isolation. A private session keeps browser state in memory
 * (or in an adapter-owned temporary directory that is removed at shutdown) and never
 * reuses it on the next application launch.</p>
 *
 * @param id stable session id, scoped to the application
 * @param storage persistent or private browser storage
 * @param userAgent optional complete user-agent override
 */
public record WebViewSessionConfig(
        String id,
        Storage storage,
        Optional<String> userAgent) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{1,64}");

    /** The default persistent session used by windows that do not opt in explicitly. */
    public static final WebViewSessionConfig DEFAULT = persistent("default").build();

    /** Browser-storage lifetime. */
    public enum Storage {
        /**
         * Request reuse of cookies, cache and other site data on later launches.
         * Adapters reject named profiles they cannot persist safely; see platform documentation.
         */
        PERSISTENT,
        /** Isolate site data from disk-backed sessions and discard it at shutdown. */
        PRIVATE
    }

    public WebViewSessionConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(userAgent, "userAgent");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "WebView session id must match [a-zA-Z0-9._-]{1,64}");
        }
        userAgent.ifPresent(WebViewSessionConfig::validateUserAgent);
    }

    /** Starts a persistent session builder. */
    public static Builder persistent(String id) {
        return new Builder(id, Storage.PERSISTENT);
    }

    /** Starts a private session builder. */
    public static Builder privateSession(String id) {
        return new Builder(id, Storage.PRIVATE);
    }

    private static void validateUserAgent(String value) {
        if (value.isBlank() || value.length() > 1024 || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "WebView user agent must be 1..1024 printable characters");
        }
    }

    /** Fluent builder for optional session settings. */
    public static final class Builder {
        private final String id;
        private final Storage storage;
        private Optional<String> userAgent = Optional.empty();

        private Builder(String id, Storage storage) {
            this.id = Objects.requireNonNull(id, "id");
            this.storage = Objects.requireNonNull(storage, "storage");
        }

        /** Overrides the complete browser user-agent string. */
        public Builder userAgent(String value) {
            this.userAgent = Optional.of(Objects.requireNonNull(value, "value"));
            return this;
        }

        public WebViewSessionConfig build() {
            return new WebViewSessionConfig(id, storage, userAgent);
        }
    }
}
