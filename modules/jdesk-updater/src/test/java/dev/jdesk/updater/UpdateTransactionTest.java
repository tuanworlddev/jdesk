package dev.jdesk.updater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private static VerifiedUpdate verified(Path p)throws Exception{return new VerifiedUpdate(p,Files.size(p),"a".repeat(64));}
}
