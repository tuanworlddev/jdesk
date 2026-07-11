package dev.jdesk.api;

/**
 * Safe, public error codes. These are the only error identifiers that may cross the
 * IPC boundary to the frontend. Never attach class names, stack traces, file paths,
 * or internal exception messages to them in production.
 */
public enum ErrorCode {
    INVALID_REQUEST,
    PROTOCOL_VERSION_UNSUPPORTED,
    STALE_NONCE,
    UNKNOWN_COMMAND,
    CAPABILITY_DENIED,
    PAYLOAD_TOO_LARGE,
    LIMIT_EXCEEDED,
    TIMEOUT,
    CANCELLED,
    SERIALIZATION_ERROR,
    NAVIGATION_BLOCKED,
    ASSET_NOT_FOUND,
    WINDOW_CLOSED,
    ALREADY_CLOSED,
    ILLEGAL_STATE,
    INTERNAL_ERROR
}
