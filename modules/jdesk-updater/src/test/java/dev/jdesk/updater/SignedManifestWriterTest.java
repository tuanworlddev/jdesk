package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The writer must produce manifests the verifier accepts, and only with the right key. */
class SignedManifestWriterTest {

    @TempDir
    Path temp;

    @Test
    void writesAManifestTheVerifierAccepts() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path pkg = temp.resolve("app-1.2.0.zip");
        Files.writeString(pkg, "the packaged bytes");

        byte[] json = SignedManifestWriter.write(pkg, "1.2.0", UpdateChannel.STABLE,
                "https://dl.example.com/app-1.2.0.zip", "1.0.0", 25,
                1_780_000_000L, keys.getPrivate());

        UpdateManifest manifest = SignedManifestVerifier.verify(json, 8192, keys.getPublic());
        assertThat(manifest.version()).isEqualTo("1.2.0");
        assertThat(manifest.releaseChannel()).isEqualTo(UpdateChannel.STABLE);
        assertThat(manifest.rolloutPercentage()).isEqualTo(25);
        assertThat(manifest.minimumCurrentVersion()).isEqualTo("1.0.0");
        assertThat(manifest.size()).isEqualTo(Files.size(pkg));
    }

    @Test
    void separateManifestKeyStillVerifiesUnderThatKey() throws Exception {
        KeyPair packageKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair manifestKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path pkg = temp.resolve("app-2.0.0.zip");
        Files.writeString(pkg, "payload");

        byte[] json = SignedManifestWriter.write(pkg, "2.0.0", UpdateChannel.BETA,
                "https://dl.example.com/app-2.0.0.zip", null, 100,
                1_780_000_000L, packageKeys.getPrivate(), manifestKeys.getPrivate());

        // Verifies under the manifest key...
        assertThat(SignedManifestVerifier.verify(json, 8192, manifestKeys.getPublic()).version())
                .isEqualTo("2.0.0");
        // ...but not under the wrong (package) key.
        assertThatThrownBy(() ->
                SignedManifestVerifier.verify(json, 8192, packageKeys.getPublic()))
                .isInstanceOf(UpdateVerificationException.class);
    }
}
