package dev.jdesk.updater;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A stable, anonymous per-install identifier used only to place this install in a phased
 * rollout bucket ({@link RolloutGate}). It is a random UUID persisted once under the app's
 * config directory with owner-only permissions; it carries no user or device information and
 * never leaves the machine. Regenerating it (e.g. deleting the file) simply re-buckets the
 * install for future rollouts.
 */
public final class InstallIdentity {

    private InstallIdentity() {
    }

    /**
     * Returns the install id stored at {@code file}, creating it on first use. A malformed or
     * symlinked file is rejected rather than trusted.
     */
    public static String loadOrCreate(Path file) {
        Path path = file.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Install identity file must not be a symlink");
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                String existing = Files.readString(path, StandardCharsets.UTF_8).strip();
                return UUID.fromString(existing).toString();
            }
            Files.createDirectories(path.getParent());
            String id = UUID.randomUUID().toString();
            Files.writeString(path, id, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictToOwner(path);
            return id;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Install identity file is corrupt: " + path, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX (Windows) filesystems fall back to default ACLs; the id is not secret.
        }
    }
}
