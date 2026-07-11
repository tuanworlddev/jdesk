package dev.jdesk.runtime.capability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.api.CapabilityGrant;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandDefinition;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.PermissionDecision;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.ipc.NavigationSession;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deny-by-default capability evaluation (spec section 12.1). Denials must never reveal
 * which capabilities exist or are configured.
 */
class CapabilityEngineTest {

    private static final String DENIED_MESSAGE = "Command is not allowed for this window";
    private static final WindowId MAIN = new WindowId("main");
    private static final WindowId OTHER = new WindowId("other");
    private static final String APP_ORIGIN = "jdesk://app";
    private static final String DEV_ORIGIN = "http://localhost:5173";

    private CapabilityEngine engine;
    private NavigationSession session;

    @BeforeEach
    void setUp() {
        CapabilitySet capabilities = CapabilitySet.of(Set.of(
                new CapabilityGrant("greeting:use", Set.of("main")),
                CapabilityGrant.forAllWindows("clipboard:read")));
        engine = new CapabilityEngine(capabilities, Set.of(APP_ORIGIN, DEV_ORIGIN));
        session = new NavigationSession();
    }

    private static CommandDefinition command(String name, Optional<String> capability) {
        return new CommandDefinition(name, capability, Void.class, Optional.empty(),
                (request, context) -> CompletableFuture.completedFuture(null));
    }

    private static CommandDefinition greet() {
        return command("greeting.greet", Optional.of("greeting:use"));
    }

    @Test
    void windowScopedGrantAllowsThatWindow() {
        PermissionDecision decision = engine.evaluate(greet(), MAIN, APP_ORIGIN, session);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void allWindowsGrantAllowsEveryWindow() {
        CommandDefinition clipboard = command("clipboard.read", Optional.of("clipboard:read"));
        assertThat(engine.evaluate(clipboard, MAIN, APP_ORIGIN, session).allowed()).isTrue();
        assertThat(engine.evaluate(clipboard, OTHER, APP_ORIGIN, session).allowed()).isTrue();
    }

    @Test
    void windowScopedGrantDeniesOtherWindows() {
        PermissionDecision decision = engine.evaluate(greet(), OTHER, APP_ORIGIN, session);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.CAPABILITY_DENIED);
        assertThat(decision.publicReason()).isEqualTo(DENIED_MESSAGE);
    }

    @Test
    void ungrantedCapabilityIsDenied() {
        CommandDefinition secret = command("fs.readAll", Optional.of("fs:read-all"));
        PermissionDecision decision = engine.evaluate(secret, MAIN, APP_ORIGIN, session);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.CAPABILITY_DENIED);
    }

    @Test
    void originOutsideAllowedSetIsDenied() {
        PermissionDecision decision = engine.evaluate(greet(), MAIN, "https://evil.example", session);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.CAPABILITY_DENIED);
        assertThat(decision.publicReason()).isEqualTo(DENIED_MESSAGE);
    }

    @Test
    void malformedOriginStringIsDeniedNotThrown() {
        PermissionDecision decision = engine.evaluate(greet(), MAIN, "not a valid origin", session);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.CAPABILITY_DENIED);
    }

    @Test
    void allowedOriginComparisonUsesNormalizedForm() {
        // Different textual form of the same dev origin must still be allowed.
        PermissionDecision decision = engine.evaluate(greet(), MAIN, "HTTP://LOCALHOST:5173", session);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void invalidatedSessionIsStaleNonce() {
        session.invalidate();
        PermissionDecision decision = engine.evaluate(greet(), MAIN, APP_ORIGIN, session);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.STALE_NONCE);
    }

    @Test
    void nullSessionIsStaleNonce() {
        PermissionDecision decision = engine.evaluate(greet(), MAIN, APP_ORIGIN, null);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.STALE_NONCE);
    }

    @Test
    void publicCommandIsAllowedForAllowedOrigin() {
        CommandDefinition open = command("app.version", Optional.empty());
        assertThat(engine.evaluate(open, MAIN, APP_ORIGIN, session).allowed()).isTrue();
        assertThat(engine.evaluate(open, OTHER, DEV_ORIGIN, session).allowed()).isTrue();
    }

    @Test
    void publicCommandIsStillDeniedForDisallowedOrigin() {
        CommandDefinition open = command("app.version", Optional.empty());
        PermissionDecision decision = engine.evaluate(open, MAIN, "https://evil.example", session);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorCode()).isEqualTo(ErrorCode.CAPABILITY_DENIED);
    }

    @Test
    void denialNeverNamesTheCapability() {
        PermissionDecision decision = engine.evaluate(greet(), OTHER, APP_ORIGIN, session);
        assertThat(decision.publicReason())
                .isEqualTo(DENIED_MESSAGE)
                .doesNotContain("greeting")
                .doesNotContain("greeting:use");
    }
}
