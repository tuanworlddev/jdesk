package dev.jdesk.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginModelTest {

    @TempDir
    Path temp;

    private static String hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sign(byte[] bytes, KeyPair keys) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(bytes);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    // ---- manifest ----

    @Test
    void parsesAValidManifestAndDefaultsCapabilities() {
        byte[] json = ("{\"pluginId\":\"dev.acme.fs\",\"version\":\"1.0.0\",\"sha256\":\""
                + "0".repeat(64) + "\"}").getBytes(StandardCharsets.UTF_8);
        PluginManifest manifest = PluginManifest.parse(json, 4096);
        assertThat(manifest.pluginId()).isEqualTo("dev.acme.fs");
        assertThat(manifest.capabilities()).isEmpty();
        assertThat(manifest.isSigned()).isFalse();
    }

    @Test
    void rejectsBadIdSizeAndHash() {
        assertThatThrownBy(() -> new PluginManifest("Bad Id", "1", Set.of(), "0".repeat(64), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginManifest("ok", "1", Set.of(), "xyz", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PluginManifest.parse(new byte[5000], 4096))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- capability gate (deny-by-default) ----

    @Test
    void authorizesOnlyWhenEveryDeclaredCapabilityIsGranted() {
        PluginManifest manifest = new PluginManifest("dev.acme.fs", "1.0.0",
                Set.of("fs:read", "fs:write"), "0".repeat(64), null);

        assertThat(PluginAuthorization.isAuthorized(manifest, Set.of("fs:read", "fs:write", "net")))
                .isTrue();
        assertThat(PluginAuthorization.ungrantedCapabilities(manifest, Set.of("fs:read")))
                .containsExactly("fs:write");
        assertThatExceptionOfType(PluginSecurityException.class)
                .isThrownBy(() -> PluginAuthorization.authorize(manifest, Set.of("fs:read")))
                .withMessageContaining("fs:write");
    }

    @Test
    void aPluginNeedingNothingIsTriviallyAuthorized() {
        PluginManifest manifest = new PluginManifest("dev.acme.noop", "1.0.0",
                Set.of(), "0".repeat(64), null);
        PluginAuthorization.authorize(manifest, Set.of()); // no throw
    }

    // ---- integrity + signature ----

    @Test
    void unsignedPluginPassesWhenHashMatchesAndFailsWhenTampered() throws Exception {
        Path jar = temp.resolve("plugin.jar");
        Files.write(jar, "plugin bytes".getBytes(StandardCharsets.UTF_8));
        PluginManifest ok = new PluginManifest("dev.acme.p", "1.0.0", Set.of(),
                hex(Files.readAllBytes(jar)), null);
        PluginIntegrity.verify(jar, ok); // no throw

        Files.write(jar, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThatExceptionOfType(PluginSecurityException.class)
                .isThrownBy(() -> PluginIntegrity.verify(jar, ok))
                .withMessageContaining("hash");
    }

    @Test
    void signedPluginRequiresAValidSignatureUnderTheTrustRoot() throws Exception {
        KeyPair trusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path jar = temp.resolve("signed.jar");
        byte[] bytes = "signed plugin bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, bytes);
        PluginManifest manifest = new PluginManifest("dev.acme.signed", "2.0.0", Set.of(),
                hex(bytes), sign(bytes, trusted));

        PluginIntegrity.verify(jar, manifest, trusted.getPublic()); // valid

        assertThatExceptionOfType(PluginSecurityException.class)
                .isThrownBy(() -> PluginIntegrity.verify(jar, manifest, attacker.getPublic()))
                .withMessageContaining("signature");
        // A signed manifest with no trust root is refused rather than trusted.
        assertThatExceptionOfType(PluginSecurityException.class)
                .isThrownBy(() -> PluginIntegrity.verify(jar, manifest))
                .withMessageContaining("trust root");
    }
}
