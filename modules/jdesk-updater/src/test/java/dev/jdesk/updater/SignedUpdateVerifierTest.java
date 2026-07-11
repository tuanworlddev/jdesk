package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignedUpdateVerifierTest {
    @TempDir Path temp;
    KeyPair keys;

    @BeforeEach void keys() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test void acceptsAuthenticPackage() throws Exception {
        Path file = packageFile("authentic");
        VerifiedUpdate update = SignedUpdateVerifier.verify(file, 1024,
                hash(file), signature(file, keys), keys.getPublic());
        assertThat(update.size()).isEqualTo(9);
    }

    @Test void rejectsWrongHashWrongSignatureAndTampering() throws Exception {
        Path file = packageFile("authentic");
        String hash = hash(file); String signature = signature(file, keys);
        assertThatThrownBy(() -> SignedUpdateVerifier.verify(file, 1024, "0".repeat(64),
                signature, keys.getPublic())).hasMessage("Update package hash mismatch");
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> SignedUpdateVerifier.verify(file, 1024, hash,
                signature(file, other), keys.getPublic())).hasMessage("Update package signature mismatch");
        Files.writeString(file, "tampered");
        assertThatThrownBy(() -> SignedUpdateVerifier.verify(file, 1024, hash,
                signature, keys.getPublic())).hasMessage("Update package hash mismatch");
    }

    @Test void rejectsOversizeAndSymlink() throws Exception {
        Path file = packageFile("authentic");
        assertThatThrownBy(() -> SignedUpdateVerifier.verify(file, 2, hash(file),
                signature(file, keys), keys.getPublic())).hasMessage("Update package is too large");
        Path link = temp.resolve("link"); Files.createSymbolicLink(link, file);
        assertThatThrownBy(() -> SignedUpdateVerifier.verify(link, 1024, hash(file),
                signature(file, keys), keys.getPublic())).hasMessage("Update package must be a regular file");
    }

    private Path packageFile(String content) throws Exception {
        Path file = temp.resolve("update.pkg"); Files.writeString(file, content); return file;
    }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static String signature(Path file, KeyPair pair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate());
        signer.update(Files.readAllBytes(file)); return Base64.getEncoder().encodeToString(signer.sign());
    }
}
