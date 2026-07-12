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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTransactionTest {
    @TempDir Path temp;

    @Test void activatesNPlusOneAndRollsBackWithoutMutatingN() throws Exception {
        UpdateTransaction tx = new UpdateTransaction(temp.resolve("install"));
        Path n = packageFile("n.pkg", "version-n");
        Path n1 = packageFile("n1.pkg", "version-n-plus-one");
        tx.stageAndActivate(verified(n), "1.0.0");
        Path first = temp.resolve("install/versions/1.0.0/package.bin");
        tx.stageAndActivate(verified(n1), "1.1.0");
        assertThat(tx.currentVersion()).isEqualTo("1.1.0");
        assertThat(Files.readString(first)).isEqualTo("version-n");
        assertThat(tx.rollback()).isEqualTo("1.0.0");
        assertThat(tx.currentVersion()).isEqualTo("1.0.0");
        assertThat(Files.readString(temp.resolve("install/versions/1.1.0/package.bin")))
                .isEqualTo("version-n-plus-one");
    }

    @Test void rejectsPackageChangedAfterSignatureVerification() throws Exception {
        UpdateTransaction tx = new UpdateTransaction(temp.resolve("install"));
        Path file = packageFile("update.pkg", "signed bytes");
        VerifiedUpdate update = verified(file);
        Files.writeString(file, "attacker replacement");

        assertThatThrownBy(() -> tx.stageAndActivate(update, "1.0.0"))
                .hasMessage("Update package changed after verification");
        assertThat(tx.currentVersion()).isNull();
        assertThat(Files.exists(temp.resolve("install/versions/1.0.0"))).isFalse();
    }

    @Test void serializesConcurrentTransactionsAndKeepsManifestValid() throws Exception {
        Path install = temp.resolve("install");
        UpdateTransaction first = new UpdateTransaction(install);
        UpdateTransaction second = new UpdateTransaction(install);
        VerifiedUpdate one = verified(packageFile("one.pkg", "one"));
        VerifiedUpdate two = verified(packageFile("two.pkg", "two"));

        CompletableFuture<Path> a = CompletableFuture.supplyAsync(() -> activate(first, one, "1.0.0"));
        CompletableFuture<Path> b = CompletableFuture.supplyAsync(() -> activate(second, two, "2.0.0"));
        CompletableFuture.allOf(a, b).join();

        String current = first.currentVersion();
        assertThat(current).isIn("1.0.0", "2.0.0");
        assertThat(first.rollback()).isIn("1.0.0", "2.0.0").isNotEqualTo(current);
    }

    @Test void recoversAnOrphanedVersionOnlyWhenItsBytesMatch() throws Exception {
        Path install = temp.resolve("install");
        UpdateTransaction tx = new UpdateTransaction(install);
        VerifiedUpdate update = verified(packageFile("same.pkg", "same"));
        Path orphan = install.resolve("versions/1.0.0");
        Files.createDirectory(orphan);
        Files.writeString(orphan.resolve("package.bin"), "same");
        Files.writeString(orphan.resolve("sha256"), update.sha256() + "\n");

        assertThat(Files.isSameFile(tx.stageAndActivate(update, "1.0.0"), orphan)).isTrue();
        assertThat(tx.currentVersion()).isEqualTo("1.0.0");
    }

    @Test void rejectsTraversalVersionAndSymlinkRoot() throws Exception {
        UpdateTransaction tx = new UpdateTransaction(temp.resolve("install"));
        Path file = packageFile("x.pkg", "x");
        assertThatThrownBy(() -> tx.stageAndActivate(verified(file), "../escape"))
                .hasMessage("Invalid update version");
        Path real = temp.resolve("real"); Files.createDirectories(real);
        Path link = temp.resolve("link"); Files.createSymbolicLink(link, real);
        assertThatThrownBy(() -> new UpdateTransaction(link))
                .hasMessage("Install root must not be a symlink");
    }

    private Path packageFile(String name,String content)throws Exception{Path p=temp.resolve(name);Files.writeString(p,content);return p;}
    private static Path activate(UpdateTransaction tx, VerifiedUpdate update, String version) {
        try {
            return tx.stageAndActivate(update, version);
        } catch (UpdateVerificationException e) {
            throw new RuntimeException(e);
        }
    }

    private static VerifiedUpdate verified(Path path) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] bytes = Files.readAllBytes(path);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(bytes);
        return SignedUpdateVerifier.verify(path, 1024 * 1024, hash,
                Base64.getEncoder().encodeToString(signer.sign()), keys.getPublic());
    }
}
