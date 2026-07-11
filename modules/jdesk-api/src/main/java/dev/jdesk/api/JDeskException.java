package dev.jdesk.api;

import java.util.Objects;

/**
 * Structured framework exception. {@link #publicMessage()} is the only message that may
 * be sent to the frontend; it must never contain secrets, paths, SQL, or internal detail.
 */
public class JDeskException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final String publicMessage;

    public JDeskException(ErrorCode code, String publicMessage) {
        this(code, publicMessage, null);
    }

    public JDeskException(ErrorCode code, String publicMessage, Throwable cause) {
        super(code + ": " + publicMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.publicMessage = Objects.requireNonNull(publicMessage, "publicMessage");
    }

    public ErrorCode code() {
        return code;
    }

    /** Message safe for the frontend. */
    public String publicMessage() {
        return publicMessage;
    }
}
