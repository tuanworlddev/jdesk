package dev.jdesk.updater;

/** Public, redacted rejection of an untrusted update package. */
public final class UpdateVerificationException extends Exception {
    private static final long serialVersionUID = 1L;
    public UpdateVerificationException(String message) { super(message); }
    public UpdateVerificationException(String message, Throwable cause) { super(message, cause); }
}
