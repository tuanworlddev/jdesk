package dev.jdesk.runtime.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filesystem asset source containment (spec sections 9.1 and 12.3: resolve only inside
 * the configured asset root, defend against symlink escape).
 */
class DirectoryAssetSourceTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private DirectoryAssetSource source() throws IOException {
        return new DirectoryAssetSource(root);
    }

    private static String read(AssetSource.Asset asset) throws IOException {
        try (InputStream in = asset.open().open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void findsFileAtRoot() throws IOException {
        Files.writeString(root.resolve("index.html"), "<html>hi</html>");
        Optional<AssetSource.Asset> asset = source().find("index.html");
        assertThat(asset).isPresent();
        assertThat(asset.get().size()).isEqualTo("<html>hi</html>".getBytes(StandardCharsets.UTF_8).length);
        assertThat(read(asset.get())).isEqualTo("<html>hi</html>");
    }

    @Test
    void findsFileInNestedDirectories() throws IOException {
        Files.createDirectories(root.resolve("a/b/c"));
        Files.writeString(root.resolve("a/b/c/app.js"), "console.log(1)");
        Optional<AssetSource.Asset> asset = source().find("a/b/c/app.js");
        assertThat(asset).isPresent();
        assertThat(read(asset.get())).isEqualTo("console.log(1)");
    }

    @Test
    void missingFileReturnsEmpty() throws IOException {
        assertThat(source().find("nope.js")).isEmpty();
        assertThat(source().find("missing/dir/file.js")).isEmpty();
    }

    @Test
    void directoryItselfIsNotServed() throws IOException {
        Files.createDirectories(root.resolve("subdir"));
        assertThat(source().find("subdir")).isEmpty();
        // The root itself (empty normalized path resolves to the root) is not a regular file.
        assertThat(source().find("")).isEmpty();
    }

    @Test
    void symlinkEscapingTheRootIsNeverServed() throws IOException {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        Path link = root.resolve("leak.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "platform does not permit symlink creation: " + e);
        }
        assertThat(source().find("leak.txt"))
                .as("symlink pointing outside the asset root must not be served")
                .isEmpty();
    }

    @Test
    void symlinkInsideTheRootIsServed() throws IOException {
        Files.writeString(root.resolve("real.js"), "export {}");
        Path link = root.resolve("alias.js");
        try {
            Files.createSymbolicLink(link, root.resolve("real.js"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "platform does not permit symlink creation: " + e);
        }
        Optional<AssetSource.Asset> asset = source().find("alias.js");
        assertThat(asset).isPresent();
        assertThat(read(asset.get())).isEqualTo("export {}");
    }

    @Test
    void symlinkedDirectoryEscapingTheRootIsNeverServed() throws IOException {
        Files.writeString(outside.resolve("file.txt"), "outside");
        Path link = root.resolve("dirlink");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "platform does not permit symlink creation: " + e);
        }
        assertThat(source().find("dirlink/file.txt")).isEmpty();
    }

    @Test
    void constructorRejectsAFileAsRoot() throws IOException {
        Path file = root.resolve("not-a-dir.txt");
        Files.writeString(file, "x");
        assertThatIOException()
                .isThrownBy(() -> new DirectoryAssetSource(file))
                .withMessageContaining("not a directory");
    }

    @Test
    void constructorRejectsMissingRoot() {
        assertThatIOException()
                .isThrownBy(() -> new DirectoryAssetSource(root.resolve("does-not-exist")));
    }
}
