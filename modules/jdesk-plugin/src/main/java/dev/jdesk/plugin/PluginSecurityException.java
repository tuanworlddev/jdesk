package dev.jdesk.plugin;

/** Raised when a plugin fails integrity, signature, or capability-grant checks. */
public final class PluginSecurityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PluginSecurityException(String message) {
        super(message);
    }

    public PluginSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
