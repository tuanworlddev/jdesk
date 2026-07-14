package dev.jdesk.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Verifies a plugin jar's bytes before it is ever put on a class/module path. The jar must hash to
 * the manifest's {@code sha256}; if the manifest is signed, an Ed25519 signature over the same bytes
 * must verify under a trusted key. This closes the gap even Tauri leaves open — plugin registries
 * there pin versions but do not enforce plugin signing — so a tampered or substituted plugin jar is
 * rejected rather than loaded.
 */
public final class PluginIntegrity {

    private PluginIntegrity() {
    }

    /** Verifies an unsigned plugin (hash only). Rejects a manifest that claims a signature. */
    public static void verify(Path pluginJar, PluginManifest manifest) {
        verify(pluginJar, manifest, null);
    }

    /**
     * Verifies {@code pluginJar} against {@code manifest}: SHA-256 always, plus the Ed25519
     * signature when the manifest is signed (which then requires {@code trustRoot}).
     *
     * @throws PluginSecurityException on any mismatch or a signed manifest without a trust root
     */
    public static void verify(Path pluginJar, PluginManifest manifest, PublicKey trustRoot) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(pluginJar);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String actual;
        try {
            actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        if (!actual.equalsIgnoreCase(manifest.sha256())) {
            throw new PluginSecurityException(
                    "Plugin " + manifest.pluginId() + " jar hash does not match its manifest");
        }
        if (manifest.isSigned()) {
            if (trustRoot == null) {
                throw new PluginSecurityException("Plugin " + manifest.pluginId()
                        + " is signed but no trust root was provided to verify it");
            }
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(trustRoot);
                verifier.update(bytes);
                if (!verifier.verify(Base64.getDecoder().decode(manifest.signature()))) {
                    throw new PluginSecurityException(
                            "Plugin " + manifest.pluginId() + " signature is invalid");
                }
            } catch (GeneralSecurityException e) {
                throw new PluginSecurityException(
                        "Could not verify plugin " + manifest.pluginId() + " signature", e);
            }
        }
    }
}
