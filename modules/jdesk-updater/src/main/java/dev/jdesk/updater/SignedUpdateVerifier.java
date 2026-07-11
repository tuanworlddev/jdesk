package dev.jdesk.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Verifies hash and Ed25519 signature over raw package bytes in one streaming pass. */
public final class SignedUpdateVerifier {
    private SignedUpdateVerifier() { }

    public static VerifiedUpdate verify(Path packagePath, long maxBytes, String expectedSha256,
            String signatureBase64, PublicKey publicKey) throws UpdateVerificationException {
        Objects.requireNonNull(packagePath, "packagePath");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        String expected = normalizeHash(expectedSha256);
        try {
            if (Files.isSymbolicLink(packagePath)
                    || !Files.isRegularFile(packagePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateVerificationException("Update package must be a regular file");
            }
            long declaredSize = Files.size(packagePath);
            if (declaredSize > maxBytes) throw new UpdateVerificationException("Update package is too large");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(Objects.requireNonNull(publicKey, "publicKey"));
            long size = 0;
            byte[] buffer = new byte[128 * 1024];
            try (InputStream input = Files.newInputStream(packagePath)) {
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read == 0) continue;
                    size += read;
                    if (size > maxBytes) throw new UpdateVerificationException("Update package is too large");
                    digest.update(buffer, 0, read);
                    signature.update(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new UpdateVerificationException("Update package hash mismatch");
            }
            byte[] signatureBytes;
            try { signatureBytes = Base64.getDecoder().decode(signatureBase64); }
            catch (IllegalArgumentException e) { throw new UpdateVerificationException("Invalid update signature encoding"); }
            if (!signature.verify(signatureBytes)) {
                throw new UpdateVerificationException("Update package signature mismatch");
            }
            return new VerifiedUpdate(packagePath.toRealPath(), size, actual);
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new UpdateVerificationException("Could not verify update package", e);
        }
    }

    private static String normalizeHash(String value) throws UpdateVerificationException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}"))
            throw new UpdateVerificationException("Invalid expected SHA-256");
        return value.toLowerCase(Locale.ROOT);
    }
}
