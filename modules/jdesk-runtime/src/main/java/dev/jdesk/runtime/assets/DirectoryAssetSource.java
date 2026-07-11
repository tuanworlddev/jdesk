package dev.jdesk.runtime.assets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Serves assets from a directory. Containment is enforced on the real (symlink-resolved)
 * path: a symlink pointing outside the root can never be served. TOCTOU between the
 * check and the open is narrowed by opening from the resolved real path.
 */
public final class DirectoryAssetSource implements AssetSource {
    private final Path realRoot;

    public DirectoryAssetSource(Path root) throws IOException {
        this.realRoot = root.toRealPath();
        if (!Files.isDirectory(realRoot)) {
            throw new IOException("Asset root is not a directory");
        }
    }

    /** The symlink-resolved asset root (used by the dev-mode reload watcher). */
    public Path root() {
        return realRoot;
    }

    @Override
    public Optional<Asset> find(String normalizedPath) throws IOException {
        Path candidate = realRoot;
        for (String segment : normalizedPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            candidate = candidate.resolve(segment);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) {
            return Optional.empty(); // symlink escape or not a regular file
        }
        long size = Files.size(real);
        return Optional.of(new Asset(size, new StreamSupplier() {
            @Override
            public InputStream open() throws IOException {
                return Files.newInputStream(real);
            }

            @Override
            public InputStream openAt(long offset) throws IOException {
                SeekableByteChannel channel = Files.newByteChannel(real);
                try {
                    channel.position(offset);
                } catch (IOException | RuntimeException e) {
                    channel.close();
                    throw e;
                }
                return Channels.newInputStream(channel);
            }
        }));
    }
}
