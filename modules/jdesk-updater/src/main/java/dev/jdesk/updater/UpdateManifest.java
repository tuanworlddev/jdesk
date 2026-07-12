package dev.jdesk.updater;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Signed update metadata. The manifest signature covers every field except itself; the
 * package signature independently covers the raw downloaded package bytes.
 */
public record UpdateManifest(
        int schemaVersion,
        String version,
        String channel,
        String packageUri,
        long size,
        String sha256,
        String packageSignature,
        long publishedAtEpochSeconds,
        String minimumCurrentVersion,
        String manifestSignature) {
    public static final int CURRENT_SCHEMA = 1;

    public UpdateManifest {
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported update manifest schema");
        }
        ReleaseVersion.parse(version);
        UpdateChannel.parse(channel);
        URI uri = URI.create(Objects.requireNonNull(packageUri, "packageUri"));
        if (!uri.isAbsolute() || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Package URI must be absolute and contain no credentials");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Package size must be positive");
        }
        sha256 = normalizeHash(sha256);
        requireBase64(packageSignature, "package signature");
        if (publishedAtEpochSeconds < 0
                || publishedAtEpochSeconds > Instant.now().plusSeconds(86400).getEpochSecond()) {
            throw new IllegalArgumentException("Manifest publication time is invalid");
        }
        if (minimumCurrentVersion != null && !minimumCurrentVersion.isBlank()) {
            ReleaseVersion.parse(minimumCurrentVersion);
        } else {
            minimumCurrentVersion = null;
        }
        requireBase64(manifestSignature, "manifest signature");
    }

    /** Deterministic length-prefixed representation authenticated by manifestSignature. */
    public byte[] signingPayload() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(schemaVersion);
                writeString(output, version);
                writeString(output, channel.toLowerCase(Locale.ROOT));
                writeString(output, packageUri);
                output.writeLong(size);
                writeString(output, sha256);
                writeString(output, packageSignature);
                output.writeLong(publishedAtEpochSeconds);
                writeString(output, minimumCurrentVersion == null ? "" : minimumCurrentVersion);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create manifest signing payload", e);
        }
    }

    public ReleaseVersion releaseVersion() {
        return ReleaseVersion.parse(version);
    }

    public UpdateChannel releaseChannel() {
        return UpdateChannel.parse(channel);
    }

    public URI packageLocation() {
        return URI.create(packageUri);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String normalizeHash(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static void requireBase64(String value, String label) {
        try {
            if (value == null || Base64.getDecoder().decode(value).length != 64) {
                throw new IllegalArgumentException("Invalid " + label);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + label, e);
        }
    }
}
