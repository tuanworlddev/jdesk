package dev.jdesk.updater;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/** Strictly parses and authenticates an update manifest with Ed25519. */
public final class SignedManifestVerifier {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(8192).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SignedManifestVerifier() {
    }

    public static UpdateManifest verify(byte[] json, int maxBytes, PublicKey publicKey)
            throws UpdateVerificationException {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(publicKey, "publicKey");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (json.length > maxBytes) {
            throw new UpdateVerificationException("Update manifest is too large");
        }
        try {
            UpdateManifest manifest = MAPPER.readValue(json, UpdateManifest.class);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(manifest.signingPayload());
            if (!verifier.verify(Base64.getDecoder().decode(manifest.manifestSignature()))) {
                throw new UpdateVerificationException("Update manifest signature mismatch");
            }
            return manifest;
        } catch (UpdateVerificationException e) {
            throw e;
        } catch (GeneralSecurityException | RuntimeException | java.io.IOException e) {
            throw new UpdateVerificationException("Could not verify update manifest", e);
        }
    }
}
