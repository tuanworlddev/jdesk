package dev.jdesk.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Versioned activation with immutable versions and one atomic current/previous manifest. */
public final class UpdateTransaction {
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,63}");
    private static final String STATE_FILE = "activation.properties";
    private static final String LOCK_FILE = ".update.lock";
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS =
            new ConcurrentHashMap<>();

    private final Path root;
    private final Path versions;

    public UpdateTransaction(Path installRoot) throws UpdateVerificationException {
        Path requested = Objects.requireNonNull(installRoot, "installRoot")
                .toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(requested)) {
                throw new UpdateVerificationException("Install root must not be a symlink");
            }
            Files.createDirectories(requested);
            root = requested.toRealPath(LinkOption.NOFOLLOW_LINKS);
            restrictToOwner(root);
            Path versionDirectory = root.resolve("versions");
            if (Files.isSymbolicLink(versionDirectory)) {
                throw new UpdateVerificationException("Versions directory must not be a symlink");
            }
            Files.createDirectories(versionDirectory);
            versions = versionDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            restrictToOwner(versions);
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateVerificationException("Could not initialize update root", e);
        }
    }

    public Path stageAndActivate(VerifiedUpdate update, String version)
            throws UpdateVerificationException {
        Objects.requireNonNull(update, "update");
        requireVersion(version);
        return withProcessLock(() -> stageAndActivateLocked(update, version));
    }

    public String rollback() throws UpdateVerificationException {
        return withProcessLock(() -> {
            Activation state = readActivation();
            if (state.previous() == null) {
                throw new UpdateVerificationException("No previous version to roll back to");
            }
            requireInstalledVersion(state.previous(), "Previous version is missing");
            writeActivation(new Activation(state.previous(), state.current(), false, 0));
            return state.previous();
        });
    }

    public String currentVersion() throws UpdateVerificationException {
        return withProcessLock(() -> readActivation().current());
    }

    /**
     * Called by the launcher before starting the selected version. A pending version gets
     * one launch attempt. If it did not confirm health, the next call atomically rolls
     * back to the previous version.
     */
    public UpdateLaunch prepareLaunch() throws UpdateVerificationException {
        return withProcessLock(() -> {
            Activation state = readActivation();
            if (state.current() == null) {
                throw new UpdateVerificationException("No active version is installed");
            }
            requireInstalledVersion(state.current(), "Current version is missing");
            if (!state.pending()) {
                return launch(state.current(), false);
            }
            if (state.launchAttempts() == 0 || state.previous() == null) {
                writeActivation(new Activation(state.current(), state.previous(), true,
                        state.launchAttempts() + 1));
                return launch(state.current(), false);
            }
            requireInstalledVersion(state.previous(), "Previous version is missing");
            writeActivation(new Activation(state.previous(), state.current(), false, 0));
            return launch(state.previous(), true);
        });
    }

    /** Marks the running version healthy, preventing automatic rollback. */
    public void confirmHealthy(String version) throws UpdateVerificationException {
        requireVersion(version);
        withProcessLock(() -> {
            Activation state = readActivation();
            if (!version.equals(state.current())) {
                throw new UpdateVerificationException(
                        "Cannot confirm a version that is not current");
            }
            requireInstalledVersion(version, "Current version is missing");
            writeActivation(new Activation(state.current(), state.previous(), false, 0));
            return null;
        });
    }

    public void confirmHealthy(UpdateLaunch launch) throws UpdateVerificationException {
        Objects.requireNonNull(launch, "launch");
        confirmHealthy(launch.version());
    }

    public boolean isCurrentPending() throws UpdateVerificationException {
        return withProcessLock(() -> readActivation().pending());
    }

    private UpdateLaunch launch(String version, boolean rolledBack) {
        return new UpdateLaunch(version, versions.resolve(version).resolve("package.bin"),
                rolledBack);
    }

    private Path stageAndActivateLocked(VerifiedUpdate update, String version)
            throws UpdateVerificationException {
        Path target = versions.resolve(version);
        Activation state = readActivation();
        if (version.equals(state.current())) {
            requireInstalledVersion(version, "Current version is missing");
            return target;
        }

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExistingVersion(target, update.sha256());
        } else {
            stageNewVersion(update, target);
        }
        writeActivation(new Activation(version, state.current(), true, 0));
        return target;
    }

    private void stageNewVersion(VerifiedUpdate update, Path target)
            throws UpdateVerificationException {
        Path staging = versions.resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectory(staging);
            restrictToOwner(staging);
            update.copyVerifiedTo(staging.resolve("package.bin"));
            restrictToOwner(staging.resolve("package.bin"));
            Files.writeString(staging.resolve("sha256"), update.sha256() + "\n",
                    java.nio.charset.StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictToOwner(staging.resolve("sha256"));
            forceFile(staging.resolve("package.bin"));
            forceFile(staging.resolve("sha256"));
            moveAtomic(staging, target);
            forceDirectory(versions);
        } catch (UpdateVerificationException e) {
            deleteStaging(staging);
            throw e;
        } catch (IOException e) {
            deleteStaging(staging);
            throw new UpdateVerificationException("Could not activate update", e);
        }
    }

    private void verifyExistingVersion(Path target, String expectedHash)
            throws UpdateVerificationException {
        if (Files.isSymbolicLink(target)
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new UpdateVerificationException("Version target is not a directory");
        }
        Path packageFile = target.resolve("package.bin");
        Path hashFile = target.resolve("sha256");
        try {
            String existing = Files.readString(hashFile,
                    java.nio.charset.StandardCharsets.US_ASCII).strip();
            String actual = sha256(packageFile);
            if (!existing.equals(expectedHash) || !actual.equals(expectedHash)) {
                throw new UpdateVerificationException(
                        "Version already exists with different package bytes");
            }
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateVerificationException("Could not inspect existing version", e);
        }
    }

    private void requireInstalledVersion(String version, String message)
            throws UpdateVerificationException {
        requireVersion(version);
        Path directory = versions.resolve(version);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new UpdateVerificationException(message);
        }
        Path packageFile = directory.resolve("package.bin");
        Path hashFile = directory.resolve("sha256");
        try {
            if (Files.isSymbolicLink(packageFile) || Files.isSymbolicLink(hashFile)
                    || !Files.isRegularFile(packageFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(hashFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateVerificationException(message);
            }
            String recorded = Files.readString(hashFile,
                    java.nio.charset.StandardCharsets.US_ASCII).strip();
            if (!recorded.matches("[0-9a-f]{64}") || !recorded.equals(sha256(packageFile))) {
                throw new UpdateVerificationException(
                        "Installed version integrity check failed");
            }
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateVerificationException(
                    "Could not verify installed version integrity", e);
        }
    }

    private Activation readActivation() throws UpdateVerificationException {
        Path state = root.resolve(STATE_FILE);
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            return new Activation(null, null, false, 0);
        }
        if (Files.isSymbolicLink(state)
                || !Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            throw new UpdateVerificationException("Invalid activation manifest");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(state, LinkOption.NOFOLLOW_LINKS)) {
            properties.load(input);
        } catch (IOException e) {
            throw new UpdateVerificationException("Could not read activation manifest", e);
        }
        String current = blankToNull(properties.getProperty("current"));
        String previous = blankToNull(properties.getProperty("previous"));
        String pendingValue = properties.getProperty("pending", "false");
        if (!"true".equals(pendingValue) && !"false".equals(pendingValue)) {
            throw new UpdateVerificationException("Invalid activation pending state");
        }
        boolean pending = Boolean.parseBoolean(pendingValue);
        int launchAttempts;
        try {
            launchAttempts = Integer.parseInt(properties.getProperty("launchAttempts", "0"));
        } catch (NumberFormatException e) {
            throw new UpdateVerificationException("Invalid activation launch attempts", e);
        }
        if (launchAttempts < 0 || !pending && launchAttempts != 0) {
            throw new UpdateVerificationException("Invalid activation health state");
        }
        if (current != null) {
            requireVersion(current);
        }
        if (previous != null) {
            requireVersion(previous);
        }
        return new Activation(current, previous, pending, launchAttempts);
    }

    private void writeActivation(Activation activation) throws UpdateVerificationException {
        if (activation.current() == null) {
            throw new UpdateVerificationException("Activation requires a current version");
        }
        requireVersion(activation.current());
        if (activation.previous() != null) {
            requireVersion(activation.previous());
        }
        if (activation.launchAttempts() < 0
                || !activation.pending() && activation.launchAttempts() != 0) {
            throw new UpdateVerificationException("Invalid activation health state");
        }
        Path temporary = root.resolve("." + STATE_FILE + "-" + UUID.randomUUID());
        Properties properties = new Properties();
        properties.setProperty("current", activation.current());
        if (activation.previous() != null) {
            properties.setProperty("previous", activation.previous());
        }
        properties.setProperty("pending", Boolean.toString(activation.pending()));
        properties.setProperty("launchAttempts",
                Integer.toString(activation.launchAttempts()));
        try (OutputStream output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            properties.store(output, "JDesk update activation");
        } catch (IOException e) {
            throw new UpdateVerificationException("Could not write activation manifest", e);
        }
        try {
            forceFile(temporary);
            restrictToOwner(temporary);
            moveAtomicReplace(temporary, root.resolve(STATE_FILE));
            forceDirectory(root);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original activation failure.
            }
            throw new UpdateVerificationException("Could not update activation manifest", e);
        }
    }

    private <T> T withProcessLock(CheckedOperation<T> operation)
            throws UpdateVerificationException {
        Path lockPath = root.resolve(LOCK_FILE);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(lockPath, LinkOption.NOFOLLOW_LINKS,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                restrictToOwner(lockPath);
                if (!lock.isValid()) {
                    throw new UpdateVerificationException("Update transaction lock is invalid");
                }
                return operation.run();
            } catch (UpdateVerificationException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new UpdateVerificationException("Could not lock update transaction", e);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private static String sha256(Path file) throws IOException, UpdateVerificationException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new UpdateVerificationException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[128 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Update filesystem does not support atomic activation", e);
        }
    }

    private static void moveAtomicReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Update filesystem does not support atomic state replacement", e);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is unavailable on Windows and some providers.
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path,
                java.nio.file.attribute.PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(java.util.List.of(ownerOnly));
            return;
        }
        throw new IOException("Filesystem cannot enforce owner-only permissions: " + path);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void requireVersion(String value) throws UpdateVerificationException {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new UpdateVerificationException("Invalid update version");
        }
    }

    private static void deleteStaging(Path directory) {
        try {
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (var children = Files.list(directory)) {
                    children.forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort cleanup of a private staging directory.
                        }
                    });
                }
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A stale .staging-* directory is never considered an installed version.
        }
    }

    @FunctionalInterface
    private interface CheckedOperation<T> {
        T run() throws UpdateVerificationException;
    }

    private record Activation(String current, String previous, boolean pending,
                              int launchAttempts) {
    }
}
