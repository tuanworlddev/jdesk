package dev.jdesk.runtime.ipc;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One navigation session: a fresh random nonce plus request-id uniqueness tracking.
 * Invalidated on every main-frame navigation and on window close; a stale nonce can
 * never reach command execution (spec section 10.4).
 */
public final class NavigationSession {
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Bound on remembered request ids; prevents unbounded growth in one session. */
    static final int MAX_TRACKED_REQUEST_IDS = 65_536;

    private final String nonce;
    private final Set<String> usedRequestIds = new LinkedHashSet<>();
    private volatile boolean invalidated;

    public NavigationSession() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        this.nonce = HexFormat.of().formatHex(bytes);
    }

    public String nonce() {
        return nonce;
    }

    public boolean isInvalidated() {
        return invalidated;
    }

    public void invalidate() {
        invalidated = true;
    }

    /** True when {@code nonce} matches this live session. */
    public boolean accepts(String nonce) {
        return !invalidated && this.nonce.equals(nonce);
    }

    /**
     * Registers a request id, enforcing uniqueness within the session.
     *
     * @return true when the id is fresh; false for duplicates or when the session has
     *         tracked its maximum (fails closed: the request is rejected, not executed)
     */
    public synchronized boolean registerRequestId(String id) {
        if (invalidated || usedRequestIds.size() >= MAX_TRACKED_REQUEST_IDS) {
            return false;
        }
        return usedRequestIds.add(id);
    }
}
