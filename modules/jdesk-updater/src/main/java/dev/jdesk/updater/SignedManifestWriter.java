package dev.jdesk.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Release-side counterpart to {@link SignedManifestVerifier}: builds the signed JSON update
 * manifest a CI job or hosted update endpoint publishes. It hashes and Ed25519-signs the package
 * bytes, then signs the manifest payload — using separate keys when given, so the package key can
 * stay offline while a manifest key rotates. Emitting this here means the framework owns the whole
 * update loop (verify <em>and</em> publish), not just the client half.
 */
public final class SignedManifestWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SignedManifestWriter() {
    }

    /** Signs the package and manifest with separate keys (manifest key may rotate independently). */
    public static byte[] write(Path packageFile, String version, UpdateChannel channel,
            String packageUri, String minimumCurrentVersion, int rolloutPercentage,
            long publishedAtEpochSeconds, PrivateKey packageKey, PrivateKey manifestKey) {
        try {
            byte[] pkg = Files.readAllBytes(packageFile);
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(pkg));
            String channelName = channel.name().toLowerCase(Locale.ROOT);
            String packageSignature = sign(pkg, packageKey);
            // A valid 64-byte placeholder satisfies the manifest invariants so we can derive the
            // signing payload (which never includes the manifest signature itself), then re-emit
            // the manifest with the real signature over that payload.
            String placeholder = Base64.getEncoder().encodeToString(new byte[64]);
            UpdateManifest unsigned = new UpdateManifest(UpdateManifest.CURRENT_SCHEMA, version,
                    channelName, packageUri, pkg.length, sha256, packageSignature,
                    publishedAtEpochSeconds, minimumCurrentVersion, rolloutPercentage, placeholder);
            String manifestSignature = sign(unsigned.signingPayload(), manifestKey);
            UpdateManifest signed = new UpdateManifest(UpdateManifest.CURRENT_SCHEMA, version,
                    channelName, packageUri, pkg.length, sha256, packageSignature,
                    publishedAtEpochSeconds, minimumCurrentVersion, rolloutPercentage,
                    manifestSignature);
            return MAPPER.writeValueAsBytes(signed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign update manifest", e);
        }
    }

    /** Signs both the package and the manifest with the same key. */
    public static byte[] write(Path packageFile, String version, UpdateChannel channel,
            String packageUri, String minimumCurrentVersion, int rolloutPercentage,
            long publishedAtEpochSeconds, PrivateKey key) {
        return write(packageFile, version, channel, packageUri, minimumCurrentVersion,
                rolloutPercentage, publishedAtEpochSeconds, key, key);
    }

    private static String sign(byte[] bytes, PrivateKey key) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
