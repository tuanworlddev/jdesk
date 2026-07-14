package dev.jdesk.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Optional;

/**
 * End-to-end update coordinator: fetches an authenticated manifest, enforces release
 * policy, downloads bounded bytes, verifies the package, and atomically stages it.
 */
public final class UpdateManager {
    private final UpdateTransaction transaction;
    private final UpdatePolicy policy;
    private final PublicKey manifestKey;
    private final PublicKey packageKey;
    private final Path downloadDirectory;
    private final HttpClient client;
    /** Stable per-install id for phased-rollout bucketing; null disables rollout gating. */
    private final String installId;

    public UpdateManager(UpdateTransaction transaction, UpdatePolicy policy,
            PublicKey publicKey, Path downloadDirectory) throws UpdateVerificationException {
        this(transaction, policy, publicKey, publicKey, downloadDirectory);
    }

    /** Uses separate trust roots so manifest rotation need not expose the package key. */
    public UpdateManager(UpdateTransaction transaction, UpdatePolicy policy,
            PublicKey manifestKey, PublicKey packageKey, Path downloadDirectory)
            throws UpdateVerificationException {
        this(transaction, policy, manifestKey, packageKey, downloadDirectory, null);
    }

    /**
     * Adds a stable install id so phased/staged rollouts apply: a manifest's
     * {@code rolloutPercentage} only stages on this install once its deterministic bucket is
     * within reach (see {@link RolloutGate}). A null id disables rollout gating (full rollout).
     */
    public UpdateManager(UpdateTransaction transaction, UpdatePolicy policy,
            PublicKey manifestKey, PublicKey packageKey, Path downloadDirectory, String installId)
            throws UpdateVerificationException {
        this.transaction = java.util.Objects.requireNonNull(transaction, "transaction");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.manifestKey = java.util.Objects.requireNonNull(manifestKey, "manifestKey");
        this.packageKey = java.util.Objects.requireNonNull(packageKey, "packageKey");
        this.installId = installId;
        this.downloadDirectory = secureDirectory(downloadDirectory);
        this.client = HttpClient.newBuilder()
                .connectTimeout(policy.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public UpdateResult checkAndStage(URI manifestUri, String currentVersion)
            throws UpdateVerificationException {
        if (!policy.enabled()) {
            return UpdateResult.of(UpdateResult.Status.DISABLED);
        }
        ReleaseVersion current;
        try {
            current = ReleaseVersion.parse(currentVersion);
        } catch (RuntimeException e) {
            throw new UpdateVerificationException("Current application version is invalid", e);
        }
        byte[] manifestBytes = fetchBytes(manifestUri, policy.maxManifestBytes(),
                "manifest");
        UpdateManifest manifest = SignedManifestVerifier.verify(
                manifestBytes, policy.maxManifestBytes(), manifestKey);
        if (manifest.releaseChannel() != policy.channel()) {
            return UpdateResult.of(UpdateResult.Status.CHANNEL_MISMATCH);
        }
        if (manifest.minimumCurrentVersion() != null
                && current.compareTo(ReleaseVersion.parse(
                        manifest.minimumCurrentVersion())) < 0) {
            return UpdateResult.of(UpdateResult.Status.CURRENT_VERSION_UNSUPPORTED);
        }
        int comparison = manifest.releaseVersion().compareTo(current);
        if (comparison == 0 || comparison < 0 && !policy.allowDowngrade()) {
            return UpdateResult.of(UpdateResult.Status.NO_UPDATE);
        }
        // Phased rollout: a newer release only stages once this install's bucket is within the
        // manifest's rolloutPercentage. Skipped when no install id is configured (full rollout).
        if (installId != null
                && !RolloutGate.eligible(installId, manifest.version(), manifest.rolloutPercentage())) {
            return UpdateResult.of(UpdateResult.Status.HELD_BACK);
        }
        if (manifest.size() > policy.maxPackageBytes()) {
            throw new UpdateVerificationException("Update package exceeds policy size");
        }

        Path downloaded = download(manifest);
        try {
            VerifiedUpdate verified = SignedUpdateVerifier.verify(downloaded,
                    policy.maxPackageBytes(), manifest.sha256(),
                    manifest.packageSignature(), packageKey);
            Path activated = transaction.stageAndActivate(verified, manifest.version());
            return new UpdateResult(UpdateResult.Status.STAGED,
                    Optional.of(manifest.version()), Optional.of(activated));
        } finally {
            try {
                Files.deleteIfExists(downloaded);
            } catch (IOException ignored) {
                // A private verified-download cache can be cleaned on the next launch.
            }
        }
    }

    private Path download(UpdateManifest manifest) throws UpdateVerificationException {
        requireTransport(manifest.packageLocation());
        HttpRequest request = HttpRequest.newBuilder(manifest.packageLocation())
                .timeout(policy.requestTimeout())
                .header("Accept-Encoding", "identity")
                .GET().build();
        Path temporary;
        try {
            temporary = Files.createTempFile(downloadDirectory, ".jdesk-update-", ".part");
            restrictToOwner(temporary);
        } catch (IOException e) {
            throw new UpdateVerificationException("Could not create update download", e);
        }
        try {
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            requireSuccess(response.statusCode(), "package");
            rejectEncoding(response);
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > policy.maxPackageBytes() || declared >= 0
                    && declared != manifest.size()) {
                throw new UpdateVerificationException("Update package length mismatch");
            }
            long copied = copyBounded(response.body(), temporary, policy.maxPackageBytes());
            if (copied != manifest.size()) {
                throw new UpdateVerificationException("Update package length mismatch");
            }
            return temporary;
        } catch (UpdateVerificationException e) {
            deleteQuietly(temporary);
            throw e;
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new UpdateVerificationException("Could not download update package", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(temporary);
            throw new UpdateVerificationException("Update download was interrupted", e);
        }
    }

    private byte[] fetchBytes(URI uri, int maxBytes, String label)
            throws UpdateVerificationException {
        requireTransport(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(policy.requestTimeout())
                .header("Accept-Encoding", "identity").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            requireSuccess(response.statusCode(), label);
            rejectEncoding(response);
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBytes) {
                throw new UpdateVerificationException("Update " + label + " is too large");
            }
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new UpdateVerificationException(
                            "Update " + label + " is too large");
                }
                return bytes;
            }
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateVerificationException("Could not fetch update " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateVerificationException("Update request was interrupted", e);
        }
    }

    private void requireTransport(URI uri) throws UpdateVerificationException {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if (policy.allowInsecureLoopback() && "http".equalsIgnoreCase(uri.getScheme())) {
            String host = uri.getHost();
            if ("127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)
                    || "::1".equals(host)) {
                return;
            }
        }
        throw new UpdateVerificationException("Update transport must use HTTPS");
    }

    private static void rejectEncoding(HttpResponse<?> response)
            throws UpdateVerificationException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (!"identity".equalsIgnoreCase(encoding)) {
            throw new UpdateVerificationException("Encoded update responses are not accepted");
        }
    }

    private static void requireSuccess(int status, String label)
            throws UpdateVerificationException {
        if (status != 200) {
            throw new UpdateVerificationException(
                    "Update " + label + " request returned HTTP " + status);
        }
    }

    private static long copyBounded(InputStream input, Path target, long maxBytes)
            throws IOException, UpdateVerificationException {
        try (input; var output = Files.newOutputStream(target,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[128 * 1024];
            long copied = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) {
                    continue;
                }
                copied += read;
                if (copied > maxBytes) {
                    throw new UpdateVerificationException("Update package is too large");
                }
                output.write(buffer, 0, read);
            }
            return copied;
        }
    }

    private static Path secureDirectory(Path requested) throws UpdateVerificationException {
        Path directory = java.util.Objects.requireNonNull(requested, "downloadDirectory")
                .toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new UpdateVerificationException(
                        "Update download directory must not be a symlink");
            }
            Files.createDirectories(directory);
            Path real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            restrictToOwner(real);
            return real;
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateVerificationException(
                    "Could not initialize update download directory", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Preserve the request failure.
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
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
}
