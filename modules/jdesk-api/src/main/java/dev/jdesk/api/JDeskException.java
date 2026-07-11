package dev.jdesk.api;

import java.util.Objects;

/**
 * Structured framework exception. {@link #publicMessage()} is the only message that may
 * be sent to the frontend; it must never contain secrets, paths, SQL, or internal detail.
 * {@link #details()} optionally carries a structured, public-safe payload (any
 * JSON-serializable value, e.g. {@code Map.of("httpStatus", 429, "retryAfterSeconds", 30)})
 * delivered to the frontend as {@code error.data} so UIs can branch on machine-readable
 * facts instead of parsing message text. The same public-safety rules apply to it.
 */
public class JDeskException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String publicMessage;
    private final transient Object details;

    public JDeskException(ErrorCode code, String publicMessage) {
        this(code, publicMessage, null, null);
    }

    public JDeskException(ErrorCode code, String publicMessage, Throwable cause) {
        this(code, publicMessage, null, cause);
    }

    /** @param details public-safe, JSON-serializable structured error data (may be null) */
    public JDeskException(ErrorCode code, String publicMessage, Object details, Throwable cause) {
        super(code + ": " + publicMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.publicMessage = Objects.requireNonNull(publicMessage, "publicMessage");
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    /** Message safe for the frontend. */
    public String publicMessage() {
        return publicMessage;
    }

    /** Structured, public-safe error data for the frontend ({@code error.data}); may be null. */
    public Object details() {
        return details;
    }
}
