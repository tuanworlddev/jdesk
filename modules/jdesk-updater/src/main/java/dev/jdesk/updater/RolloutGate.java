package dev.jdesk.updater;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Decides whether a specific install is inside a phased rollout. The decision is deterministic
 * in {@code (installId, version)}: an install stays in the same bucket for a given release across
 * every check, so raising the manifest's {@code rolloutPercentage} over time only ever lets more
 * installs in and never flips one back out. Buckets are derived by hashing so they are spread
 * evenly and independently per release (an install unlucky for one version is not for the next).
 */
final class RolloutGate {

    private RolloutGate() {
    }

    /**
     * @param installId stable per-install identifier (see {@link InstallIdentity})
     * @param version   the candidate release version
     * @param percentage rollout reach, 0..100 ({@code >=100} everyone, {@code <=0} no one)
     * @return true when this install is within the rollout for that version
     */
    static boolean eligible(String installId, String version, int percentage) {
        if (percentage >= 100) {
            return true;
        }
        if (percentage <= 0) {
            return false;
        }
        return bucket(installId, version) < percentage;
    }

    /** Deterministic bucket in {@code [0, 100)} from the first four SHA-256 bytes. */
    static int bucket(String installId, String version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (installId + "@" + version).getBytes(StandardCharsets.UTF_8));
            int value = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16)
                    | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
