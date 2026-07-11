package dev.jdesk.api;

import java.util.Objects;

/**
 * Result of a capability evaluation. {@code publicReason} is safe for the frontend and
 * must not reveal which capabilities exist or are configured.
 */
public record PermissionDecision(boolean allowed, ErrorCode errorCode, String publicReason) {
    public PermissionDecision {
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(publicReason, "publicReason");
    }

    private static final PermissionDecision ALLOWED =
            new PermissionDecision(true, ErrorCode.INTERNAL_ERROR, "allowed");

    public static PermissionDecision allow() {
        return ALLOWED;
    }

    public static PermissionDecision deny(ErrorCode code, String publicReason) {
        return new PermissionDecision(false, code, publicReason);
    }
}
