package dev.jdesk.runtime.capability;

import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.PermissionDecision;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.ipc.NavigationSession;
import java.util.Set;

/**
 * Deny-by-default capability evaluation (spec section 12.1). Runs before payload
 * deserialization and before any user command code. Denial reasons never reveal which
 * capabilities exist or are configured.
 */
public final class CapabilityEngine {
    private static final String DENIED_MESSAGE = "Command is not allowed for this window";

    private final CapabilitySet capabilities;
    private final Set<String> allowedOrigins;

    /**
     * @param allowedOrigins normalized origins that own bridge authority: the app origin
     *        and, in dev mode only, the exact dev-server origin
     */
    public CapabilityEngine(CapabilitySet capabilities, Set<String> allowedOrigins) {
        this.capabilities = capabilities;
        this.allowedOrigins = allowedOrigins.stream()
                .map(OriginNormalizer::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public PermissionDecision evaluate(
            CommandDefinition command,
            WindowId windowId,
            String currentOrigin,
            NavigationSession session) {

        if (session == null || session.isInvalidated()) {
            return PermissionDecision.deny(ErrorCode.STALE_NONCE, "Navigation session is stale");
        }
        String normalizedOrigin;
        try {
            normalizedOrigin = OriginNormalizer.normalize(currentOrigin);
        } catch (RuntimeException e) {
            return PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, DENIED_MESSAGE);
        }
        if (!allowedOrigins.contains(normalizedOrigin)) {
            return PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, DENIED_MESSAGE);
        }
        if (command.requiredCapability().isEmpty()) {
            // Only @PublicDesktopCommand commands reach the registry without a capability.
            return PermissionDecision.allow();
        }
        String required = command.requiredCapability().get();
        if (capabilities.isGranted(required, windowId)) {
            return PermissionDecision.allow();
        }
        return PermissionDecision.deny(ErrorCode.CAPABILITY_DENIED, DENIED_MESSAGE);
    }
}
