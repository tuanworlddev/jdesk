package dev.jdesk.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A package whose bytes were authenticated by {@link SignedUpdateVerifier}.
 *
 * <p>Instances cannot be constructed by consumers. Activation copies and hashes the
 * source again, so replacing or mutating the source after verification fails closed.
 */
public final class VerifiedUpdate {
    private final Path packagePath;
    private final long size;
    private final String sha256;

    VerifiedUpdate(Path packagePath, long size, String sha256) {
        this.packagePath = Objects.requireNonNull(packagePath, "packagePath");
        this.size = size;
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public Path packagePath() {
        return packagePath;
    }

    public long size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    void copyVerifiedTo(Path target) throws UpdateVerificationException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new UpdateVerificationException("SHA-256 is unavailable", e);
        }

        long copied = 0;
        byte[] buffer = new byte[128 * 1024];
        try (InputStream input = Files.newInputStream(packagePath, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) {
                    continue;
                }
                copied += read;
                if (copied > size) {
                    throw new UpdateVerificationException(
                            "Update package changed after verification");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (UpdateVerificationException e) {
            deletePartial(target);
            throw e;
        } catch (IOException | RuntimeException e) {
            deletePartial(target);
            throw new UpdateVerificationException("Could not stage verified update", e);
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (copied != size || !MessageDigest.isEqual(
                sha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            deletePartial(target);
            throw new UpdateVerificationException("Update package changed after verification");
        }
    }

    private static void deletePartial(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // The transaction also removes its private staging directory on failure.
        }
    }
}
